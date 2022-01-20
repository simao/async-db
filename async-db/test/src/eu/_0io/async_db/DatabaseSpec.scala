package eu._0io.async_db

import anorm.SqlParser._
import anorm._
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.languageFeature.implicitConversions
import scala.util.control.NoStackTrace

object MunitFutureValue {
  implicit class MunitFutureOps[T](value: Future[T]) {
    def futureValue(implicit timeout: Duration): T =
      Await.result(value, timeout)
  }
}

class DatabaseSpec extends munit.FunSuite {
  import MunitFutureValue._

  lazy val config = ConfigFactory.load().getConfig("async-db.database")

  implicit lazy val db = Database.fromConfig(config)

  implicit val timeout = munitTimeout

  test("can run sync statements") {
    val id = db.withConnectionSync { implicit c =>
      SQL("insert into async_db_ids (seq) values('hello') RETURNING id").as(scalar[Long].single)
    }

    assert(clue(id) > 0, "id bigger greater than 0")
  }

  test("can run async statements") {
    val idF  = db.withConnection { implicit c =>
      SQL("insert into async_db_ids (seq) values('hello') RETURNING id").as(scalar[Long].single)
    }

    val count  = db.withConnection { implicit c =>
      SQL("select count(id) from async_db_ids where id = {id}").on("id" -> idF.futureValue).as(scalar[Long].single)
    }

    assert(clue(idF.futureValue) > 0, "id bigger greater than 0")
    assertEquals(clue(count.futureValue), 1L, "count should 1")
  }

  test("transaction is rolled back on exception when using transactions") {
    val ex = new RuntimeException("[test] error") with NoStackTrace

    var id = 0L

    val idF  = db.withTransaction { implicit c =>
      id = SQL("insert into async_db_ids (seq) values('hello') RETURNING id").as(scalar[Long].single)
      throw ex
    }

    assertEquals(idF.failed.futureValue, ex, "must return expected error")

    val count = db.withConnection { implicit c =>
      SQL("select count(id) from async_db_ids where id = {id}").on("id" -> id).as(scalar[Long].single)
    }

    assertEquals(count.futureValue, 0L, "row should not be inserted")
  }

  test("transaction is committed if no error occurs") {
    val idF  = db.withTransaction { implicit c =>
      SQL("insert into async_db_ids (seq) values('hello') RETURNING id").as(scalar[Long].single)
    }

    val count  = db.withConnection { implicit c =>
      SQL("select count(id) from async_db_ids where id = {id}").on("id" -> idF.futureValue).as(scalar[Long].single)
    }

    assertEquals(count.futureValue, 1L, "row should be inserted")
  }

  val schema ="""
      DROP TABLE IF EXISTS async_db_ids;
      create table async_db_ids (id bigserial, seq varchar(100));
      """

  override def beforeAll(): Unit = {
    db.withConnection { implicit c =>
      SQL(schema).execute()
    }.futureValue
  }

  override def afterAll(): Unit = {
    val sql = SQL("truncate async_db_ids")
    db.withConnection(implicit c => sql.execute()).futureValue
  }
}