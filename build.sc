import mill._
import mill.scalalib._
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}

object `async-db` extends mill.Cross[AsyncDbModule]("2.12.4", "2.13.6")

class AsyncDbModule(val crossScalaVersion: String) extends CrossScalaModule with PublishModule {

  override def ivyDeps = Agg(
    ivy"com.zaxxer:HikariCP::4.0.3",
    ivy"com.typesafe:config::1.4.1",
    ivy"io.dropwizard.metrics:metrics-core:4.2.6",
  )

  def pomSettings = PomSettings(
    description = "jdbc db async support",
    organization = "eu.0io",
    url = "https://github.com/simao/async-db",
    licenses = Seq(License.`MPL-2.0`),
    versionControl = VersionControl.github("simao", "async-db"),
    developers = Seq(
      Developer("simao", "Simão Mata", "https://github.com/simao")
    )
  )

  override def sonatypeUri: String = "https://s01.oss.sonatype.org/service/local"

  override def sonatypeSnapshotUri: String = "https://s01.oss.sonatype.org/content/repositories/snapshots"

  object test extends Tests with TestModule.Munit {
    override def ivyDeps = Agg(
      ivy"org.postgresql:postgresql::42.3.1",
      ivy"ch.qos.logback:logback-classic::1.2.9",
      ivy"org.scalameta::munit::0.7.29",
      ivy"org.slf4j:jul-to-slf4j:1.7.32",
      ivy"org.playframework.anorm::anorm:2.6.10",
    )
  }

  override def publishVersion = "0.0.1"

  override def scalacOptions = Seq("-Yrangepos")
}
