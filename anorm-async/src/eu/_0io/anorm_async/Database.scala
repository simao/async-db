package eu._0io.anorm_async

import com.codahale.metrics.{Counter, Gauge, MetricRegistry}
import com.typesafe.config.Config
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.slf4j.LoggerFactory

import java.io.Closeable
import java.sql.Connection
import java.util.Properties
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent._
import javax.sql.DataSource
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

case class DatabaseConfig(poolName: String, maxConnections: Int, threadPoolQueueSize: Int, dataSourceProperties: Properties, metricRegistry: Option[MetricRegistry])

object Database {

  private lazy val log = LoggerFactory.getLogger(this.getClass)

  private def fromHikariPool(config: Config,
                             dbConfig: DatabaseConfig,
                             metricRegistry: Option[MetricRegistry]): Database = {

    val hikariCpProperties = new Properties()

    hikariCpProperties.setProperty("username", config.getString("user"))
    hikariCpProperties.setProperty("password", config.getString("password"))
    hikariCpProperties.setProperty("jdbcUrl", config.getString("jdbcurl"))

    config.getObject("db-pool.properties").asScala.foreach { case (path, value) =>
      hikariCpProperties.setProperty(path, value.unwrapped().toString)
    }

    val hconfig = new HikariConfig(hikariCpProperties)
    hconfig.setDataSourceProperties(dbConfig.dataSourceProperties)

    val ds = new HikariDataSource(hconfig)

    metricRegistry.foreach(ds.setMetricRegistry)

    val hikariCpDbConfig = dbConfig.copy(poolName = ds.getPoolName, maxConnections = ds.getMaximumPoolSize)

    log.info(s"Starting hikariCP pool with $hikariCpDbConfig")

    new Database(ds, hikariCpDbConfig)
  }

  def fromConfig(config: Config, metricRegistry: Option[MetricRegistry] = None): Database = {
    val properties = new Properties()

    config.getObject("jdbc-properties").asScala.foreach { case (path, value) =>
      properties.setProperty(path, value.unwrapped().toString)
    }

    val dbConfig = DatabaseConfig("default-pool-name", 0, config.getInt("thread-pool.queueSize"), properties, metricRegistry)

    fromHikariPool(config, dbConfig, metricRegistry)
  }
}

class Database(ds: DataSource, val databaseConfig: DatabaseConfig) {

  private lazy val log = LoggerFactory.getLogger(this.getClass)

  private val daemonThreadFactory = new ThreadFactory {
    private val threadNumber = new AtomicInteger(-1)

    override def newThread(r: Runnable): Thread = {
      val t = new Thread(r, s"anorm-async-db-pool-${threadNumber.incrementAndGet()}")
      if(!t.isDaemon)
        t.setDaemon(true)
      t
    }
  }

  private object PoolStatus extends Enumeration {
    val RUNNING, SHUTTING_DOWN = Value
  }

  private val status = new AtomicReference[PoolStatus.Value](PoolStatus.RUNNING)

  def close(): Unit = {
    if(status.compareAndSet(PoolStatus.RUNNING, PoolStatus.SHUTTING_DOWN)) {
      ioPool.shutdownNow()

      if(!ioPool.awaitTermination(30, TimeUnit.SECONDS))
        log.warn("Could not wait fora ioPool shutdown (not yet shutdown after 30 seconds)")

      //noinspection TypeCheckCanBeMatch
      if(ds.isInstanceOf[Closeable])
        ds.asInstanceOf[Closeable].close()
    }
  }

  private val ioPoolExecutor = new ThreadPoolExecutor(databaseConfig.maxConnections, databaseConfig.maxConnections,
    0L,
    TimeUnit.MILLISECONDS,
    new LinkedBlockingQueue[Runnable](databaseConfig.threadPoolQueueSize),
    daemonThreadFactory)

  databaseConfig.metricRegistry.foreach { mr =>
    mr.register(s"anorm-async.db.${databaseConfig.poolName}.io-thread-pool.active-count", new Gauge[Int] {
      override def getValue: Int = ioPoolExecutor.getActiveCount
    })

    mr.register(s"anorm-async.db.${databaseConfig.poolName}.io-thread-pool.core-pool-size", new Gauge[Int] {
      override def getValue: Int = ioPoolExecutor.getCorePoolSize
    })

    mr.register(s"anorm-async.db.${databaseConfig.poolName}.io-thread-pool.task-count", new Gauge[Long] {
      override def getValue: Long = ioPoolExecutor.getTaskCount
    })

    mr.register(s"anorm-async.db.${databaseConfig.poolName}.io-thread-pool.queue-size", new Gauge[Int] {
      override def getValue: Int = ioPoolExecutor.getQueue.size()
    })

    mr.register(s"anorm-async.db.${databaseConfig.poolName}.io-thread-pool.pool-size", new Gauge[Int] {
      override def getValue: Int = ioPoolExecutor.getPoolSize
    })
  }

  lazy val rejectedExecutionCounter: Option[Counter] = databaseConfig.metricRegistry.map { mr =>
    mr.counter(s"anorm-async.db.${databaseConfig.poolName}.io-thread-pool.rejected-count")
  }

  private val ioPool = ExecutionContext.fromExecutorService(ioPoolExecutor)

  private def submitToPool[T](fn: Promise[T] => Unit): Promise[T] = {
    val promise = Promise[T]()

    if(status.get() != PoolStatus.RUNNING)
      promise.failure(new IllegalArgumentException("Pool is shutting down, not accepting more jobs"))
    else {
      try
        ioPool.execute { () => fn.apply(promise) }
      catch {
        case ex: RejectedExecutionException =>
          rejectedExecutionCounter.foreach(_.inc())
          promise.failure(ex)
      }

      promise
    }
  }

  def withTransaction[T](fn: Connection => T): Future[T] =
    withConnection { conn =>
      val autoCommit = conn.getAutoCommit

      try {
        conn.setAutoCommit(false)
        val result = fn(conn)
        conn.commit()
        result
      } catch {
        case NonFatal(ex) =>
          conn.rollback()
          throw ex
      } finally
        conn.setAutoCommit(autoCommit)
    }

  def withConnection[T](fn: Connection => T): Future[T] = {
    val pr = submitToPool { promise: Promise[T] =>
      try {
        val conn = ds.getConnection

        try
          promise.success(fn(conn))
        finally
          conn.close()

      } catch {
        case NonFatal(ex) =>
          promise.failure(ex)
      }
    }

    pr.future
  }

  def withConnectionSync[T](fn: Connection => T): T = {
    val conn = ds.getConnection
    try
      fn(conn)
    finally
      conn.close()
  }
}
