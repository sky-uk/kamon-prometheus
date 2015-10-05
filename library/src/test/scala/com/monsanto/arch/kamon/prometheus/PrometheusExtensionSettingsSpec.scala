package com.monsanto.arch.kamon.prometheus

import akka.ConfigurationException
import com.typesafe.config.ConfigFactory
import kamon.Kamon
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.duration.DurationInt

/** Tests that the Prometheus extension is correctly getting its configuration.
  *
  * @author Daniel Solano Gómez
  */
class PrometheusExtensionSettingsSpec extends WordSpec with Matchers {
  import PrometheusExtensionSettingsSpec._

  /** Stops Kamon after each test. */
  override def withFixture(test: NoArgTest) = {
    try {
      super.withFixture(test)
    } finally {
      try {
        Kamon.shutdown()
      } catch {
        case _: NullPointerException ⇒ // occurs if Kamon did not start
      }
    }
  }

  "the Prometheus extension" when {
    "loading settings" should {
      "load the default configuration" in {
        Kamon.start(NoKamonLoggingConfig)
        val settings = Kamon.extension(Prometheus).settings

        settings.refreshInterval shouldBe 1.minute
        settings.subscriptions shouldBe DefaultSubscriptions
        settings.labels shouldBe Map.empty[String,String]
      }

      "reject configurations where the refresh interval is too short" in {
        val config = ConfigFactory.parseString("kamon.prometheus.refresh-interval = 30 milliseconds")

        Kamon.start(config.withFallback(NoKamonLoggingConfig))
        the [ConfigurationException] thrownBy {
          Kamon(Prometheus)
        } should have message "The Prometheus refresh interval (30 milliseconds) must be equal to or greater than the Kamon tick interval (10 seconds)"
      }

      "respect a refresh interval setting" in {
        val config = ConfigFactory.parseString("kamon.prometheus.refresh-interval = 1000.days")
        Kamon.start(config.withFallback(NoKamonLoggingConfig))
        Kamon(Prometheus).settings.refreshInterval shouldBe 1000.days
      }

      "respect an overridden subscription setting" in {
        val config = ConfigFactory.parseString("kamon.prometheus.subscriptions.counter = [ \"foo\" ]")
        Kamon.start(config.withFallback(NoKamonLoggingConfig))
        Kamon(Prometheus).settings.subscriptions shouldBe DefaultSubscriptions.updated("counter", List("foo"))
      }

      "respect an additional subscription setting" in {
        val config = ConfigFactory.parseString("kamon.prometheus.subscriptions.foo = [ \"bar\" ]")
        Kamon.start(config.withFallback(NoKamonLoggingConfig))
        Kamon(Prometheus).settings.subscriptions shouldBe DefaultSubscriptions + ("foo" → List("bar"))
      }

      "respect configured labels" in {
        val config = ConfigFactory.parseString("kamon.prometheus.labels.foo = \"bar\"")
        Kamon.start(config.withFallback(NoKamonLoggingConfig))
        Kamon(Prometheus).settings.labels shouldBe Map("foo" → "bar")
      }
    }

    "applying the refresh interval setting" should {
      "enable buffering when the refresh interval is longer than the tick interval" in {
        val config = ConfigFactory.parseString(
          """kamon.prometheus.refresh-interval = 5 minute
            |kamon.metric.tick-interval = 2 minutes
          """.stripMargin).withFallback(NoKamonLoggingConfig)

        Kamon.start(config)

        val extension = Kamon.extension(Prometheus)
        extension.isBuffered shouldBe true
        extension.listener should not be theSameInstanceAs(extension.buffer)
      }

      "not buffer when the refresh interval is the same as the tick interval" in {
        val config = ConfigFactory.parseString(
          """kamon.prometheus.refresh-interval = 42 days
            |kamon.metric.tick-interval = 42 days
          """.stripMargin).withFallback(NoKamonLoggingConfig)

        Kamon.start(config)

        val extension = Kamon.extension(Prometheus)
        extension.isBuffered shouldBe false
        extension.listener shouldBe theSameInstanceAs(extension.buffer)
      }
    }
  }
}

object PrometheusExtensionSettingsSpec {
  /** Produces a configuration that disables all Akka logging from within the Kamon system. */
  val NoKamonLoggingConfig = ConfigFactory.parseString(
    """kamon.internal-config.akka {
      |  loglevel = "OFF"
      |  stdout-loglevel = "OFF"
      |}
    """.stripMargin).withFallback(ConfigFactory.load())

  /** A handy map of all the default subscriptions. */
  val DefaultSubscriptions = Map(
    "histogram" → List("**"),
    "min-max-counter" → List("**"),
    "gauge" → List("**"),
    "counter" → List("**"),
    "trace" → List("**"),
    "trace-segment" → List("**"),
    "akka-actor" → List("**"),
    "akka-dispatcher" → List("**"),
    "akka-router" → List("**"),
    "system-metric" → List("**"),
    "http-server" → List("**"),
    "spray-can-server" → List("**")
  )
}
