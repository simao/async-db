# async-db - non blocking API for jdbc database connections

[![Maven Central](https://img.shields.io/maven-central/v/eu.0io/async-db_2.13)](https://search.maven.org/artifact/eu.0io/async-db_2.13/0.0.1/jar) [![Continuous integration](https://github.com/simao/async-db/actions/workflows/ci.yml/badge.svg)]()

JDBC is commonly used in Scala to access databases, however, JDBC uses blocking IO whenever it access the database, blocking the current thread. therefore developers need to be careful when using JDBC in an asynchronous application. 

async-db is a thin wrapper around JDBC that includes a preconfigured thread pool and provides a non blocking API, returning a `Future` to connect to a database.

The thread pool is a simpler version of the thread pool used by [slick](https://github.com/slick/slick/):

- It uses a blocking queue to queue requests, which are processed FIFO

- The queue is limited and this limit is configurable, once the queue is full the api caller requests will be rejected

- The threads created are daemon threads

- One thread will be created per database connection, so that jdbc blocking calls do not affect other connections.

[Hikari-CP](https://github.com/brettwooldridge/HikariCP) is used to manage the connection pool.

## How to use

Get `<version>` from the maven central bade above.

### With sbt

```
libraryDependencies += "eu.0io" %% "async-db" % "<version>"
```

### With mill

```
ivy"eu.0io::async-db:<version>"
```

You'll also need to add the JDBC drivers for the database you need to connect to.

## Configure

Create a configuration for your database and thread pools in `application.conf`:

Create or edit an `application.conf` in your classpath with the following configurations:


```
your-app {
  database {
    jdbcurl = "jdbc:postgresql://host:port/schema"

    jdbc-properties = {
      reWriteBatchedInserts = true # You can put any property specific to your JDBC connector here
    }

    thread-pool = {
      queueSize = 1000
    }

    db-pool = {
      properties = { // Hikari CP specific pool configurations, see https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby
        registerMbeans = true
        maximumPoolSize = 10
        minimumIdle = 10
      }
    }
  }
}
```

### Use

Create a `Database`:

```scala
import com.typesafe.config.ConfigFactory
import eu._0io.async_db.Database

val config = ConfigFactory.load().getConfig("your-app.database")
implicit lazy val db = Database.fromConfig(config)
```

You can then share this `db` instance on your application and execute SQL queries:

```scala
val id  = db.withConnection { c: Connection =>
  // Your jdbc code to use `Connection`
}

// id has type Future[_]
```

Run transactions with `withTransaction`:

```scala
val id  = db.withTransaction { c =>
  // Your jdbc code to use `Connection`
}
```

The transactions will be committed if the function passed as argument succeeds or is rollbacked if an exceptions is thrown. For finer control of the transaction behavior, you can use `withConnection` directly.

## Monitoring

Both `async-db` and `hikari-cp` will publish metrics using [dropwizard metrics](https://metrics.dropwizard.io/4.2.0/). This can be enabled by passing a metrics collector when initializing a `Database`:

```scala
val metricRegistry = new MetricRegistry
implicit lazy val db = Database.fromConfig(config, Option(metricRegistry))
```

You can then enable the dropwizard reporters, for example for the console reporter:

```scala
val reporter = ConsoleReporter.forRegistry(metricRegistry).convertRatesTo(TimeUnit.SECONDS).convertDurationsTo(TimeUnit.MILLISECONDS).build
reporter.start(10, TimeUnit.SECONDS)
```

Will report:

```
-- Gauges ----------------------------------------------------------------------
HikariPool-1.pool.ActiveConnections
             value = 0
HikariPool-1.pool.IdleConnections
             value = 10
HikariPool-1.pool.MaxConnections
             value = 10
HikariPool-1.pool.MinConnections
             value = 10
HikariPool-1.pool.PendingConnections
             value = 0
HikariPool-1.pool.TotalConnections
             value = 10
async-db.db.HikariPool-1.io-thread-pool.active-count
             value = 0
async-db.db.HikariPool-1.io-thread-pool.core-pool-size
             value = 10
async-db.db.HikariPool-1.io-thread-pool.pool-size
             value = 8
async-db.db.HikariPool-1.io-thread-pool.queue-size
             value = 0
async-db.db.HikariPool-1.io-thread-pool.task-count
             value = 8

-- Histograms ------------------------------------------------------------------
HikariPool-1.pool.ConnectionCreation
             count = 9
               min = 4
               max = 40
              mean = 9.78
            stddev = 10.81
            median = 6.00
              75% <= 8.00
              95% <= 40.00
              98% <= 40.00
              99% <= 40.00
            99.9% <= 40.00
HikariPool-1.pool.Usage
             count = 9
               min = 2
               max = 116
              mean = 27.56
            stddev = 39.35
            median = 4.00
              75% <= 34.00
              95% <= 116.00
              98% <= 116.00
              99% <= 116.00
            99.9% <= 116.00

-- Meters ----------------------------------------------------------------------
HikariPool-1.pool.ConnectionTimeoutRate
             count = 0
         mean rate = 0.00 events/second
     1-minute rate = 0.00 events/second
     5-minute rate = 0.00 events/second
    15-minute rate = 0.00 events/second

-- Timers ----------------------------------------------------------------------
HikariPool-1.pool.Wait
             count = 9
         mean rate = 30.69 calls/second
     1-minute rate = 0.00 calls/second
     5-minute rate = 0.00 calls/second
    15-minute rate = 0.00 calls/second
               min = 0.02 milliseconds
               max = 0.22 milliseconds
              mean = 0.06 milliseconds
            stddev = 0.06 milliseconds
            median = 0.04 milliseconds
              75% <= 0.05 milliseconds
              95% <= 0.22 milliseconds
              98% <= 0.22 milliseconds
              99% <= 0.22 milliseconds
            99.9% <= 0.22 milliseconds
```

## Build and running tests

You will need [mill](https://com-lihaoyi.github.io/mill/mill/Intro_to_Mill.html) installed.

To run tests you'll need a postgres instance:

```
docker run --name async-db -p 5432:5432 -e POSTGRES_PASSWORD=root -e POSTGRES_USER=async-db -d postgres:13.3-alpine
```

Then run tests:

`mill async-db[_].test`
