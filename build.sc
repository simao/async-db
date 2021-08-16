import mill._
import mill.scalalib._
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}

object `anorm-async` extends mill.Cross[AnormAsyncModule]("2.12.4", "2.13.6")

class AnormAsyncModule(val crossScalaVersion: String) extends CrossScalaModule with PublishModule {

  override def ivyDeps = Agg(
    ivy"com.zaxxer:HikariCP::4.0.3",
    ivy"org.playframework.anorm::anorm:2.6.10",
    ivy"com.typesafe:config::1.4.1",
    ivy"io.dropwizard.metrics:metrics-core:4.2.0",
  )

  def pomSettings = PomSettings(
    description = "anorm async support",
    organization = "eu.0io",
    url = "https://github.com/simao/anorm-async",
    licenses = Seq(License.`MPL-2.0`),
    versionControl = VersionControl.github("simao", "anorm-async"),
    developers = Seq(
      Developer("simao", "Simão Mata", "https://github.com/simao")
    )
  )

  override def sonatypeUri: String = "https://s01.oss.sonatype.org/service/local"

  override def sonatypeSnapshotUri: String = "https://s01.oss.sonatype.org/content/repositories/snapshots"

  object test extends Tests with TestModule.Munit {
    override def ivyDeps = Agg(
      ivy"org.postgresql:postgresql::42.2.23",
      ivy"ch.qos.logback:logback-classic::1.2.3",
      ivy"org.scalameta::munit::0.7.27",
      ivy"org.slf4j:jul-to-slf4j:1.7.31",
    )
  }

  override def publishVersion = "0.0.2-SNAPSHOT"

  override def scalacOptions = Seq("-Yrangepos")
}
