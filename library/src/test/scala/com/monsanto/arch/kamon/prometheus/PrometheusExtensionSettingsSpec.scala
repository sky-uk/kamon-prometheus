package com.monsanto.arch.kamon.prometheus

import java.net.Socket

import akka.ConfigurationException
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

/** Tests that the Prometheus extension is correctly getting its configuration.
  *
  * @author Daniel Solano Gómez
  */
class PrometheusExtensionSettingsSpec extends WordSpec with Matchers with Eventually {
  import PrometheusExtensionSettingsSpec._

  "the Prometheus extension" when {
    "loading settings" should {
      "load the default configuration" in {
        val settings = new PrometheusSettings(ConfigFactory.parseString("kamon.prometheus.refresh-interval = 1 hour").withFallback(NoKamonLoggingConfig))
        settings.subscriptions shouldBe DefaultSubscriptions
        settings.labels shouldBe Map.empty[String,String]
      }

      "reject configurations where the refresh interval is too short" in {
        val config = ConfigFactory.parseString("kamon.prometheus.refresh-interval = 10 seconds").withFallback(NoKamonLoggingConfig)
        // the reference config's refresh interval is already lower than the tick interval (in our test application.conf)
        the [ConfigurationException] thrownBy {
          new PrometheusSettings(config)
        } should have message "The Prometheus refresh interval (10 seconds) must be equal to or greater than the Kamon tick interval (1 hour)"
      }

      "respect a refresh interval setting" in {
        val config = ConfigFactory.parseString("kamon.prometheus.refresh-interval = 1000.days").withFallback(NoKamonLoggingConfig)
        new PrometheusSettings(config).refreshInterval shouldBe 1000.days
      }

      "respect an overridden subscription setting" in {
        val config = ConfigFactory.parseString("kamon.prometheus.subscriptions.counter = [ \"foo\" ]").withFallback(NoKamonLoggingConfig)
        new PrometheusSettings(config).subscriptions shouldBe DefaultSubscriptions.updated("counter", List("foo"))
      }

      "respect an additional subscription setting" in {
        val config = ConfigFactory.parseString("kamon.prometheus.subscriptions.foo = [ \"bar\" ]").withFallback(NoKamonLoggingConfig)
        new PrometheusSettings(config).subscriptions shouldBe DefaultSubscriptions + ("foo" → List("bar"))
      }

      "respect configured labels" in {
        val config = ConfigFactory.parseString("kamon.prometheus.labels.foo = \"bar\"").withFallback(NoKamonLoggingConfig)
        new PrometheusSettings(config).labels shouldBe Map("foo" → "bar")
      }
    }

    "applying the refresh interval setting" should {
      "enable buffering when the refresh interval is longer than the tick interval" in {
        val config = ConfigFactory.parseString(
          """kamon.prometheus.refresh-interval = 2 hours
          """.stripMargin).withFallback(NoKamonLoggingConfig)
        val extension = new PrometheusExtension(ActorSystem("test-isBuffered", config))
        extension.isBuffered shouldBe true
        extension.listener should not be theSameInstanceAs(extension.buffer)
      }

      "not buffer when the refresh interval is the same as the tick interval" in {
        val config = ConfigFactory.parseString(
          """kamon.prometheus.refresh-interval = 1 hour
          """.stripMargin).withFallback(NoKamonLoggingConfig)

        val extension = new PrometheusExtension(ActorSystem("test-notBuffered", config))
        extension.isBuffered shouldBe false
        extension.listener shouldBe theSameInstanceAs(extension.buffer)
      }
    }


    "The Prometheus Endpoint" should {
      implicit val patienceConfig = PatienceConfig(Span(10, Seconds))
      "should automatically bind to a port when configured to do so" in {
        val settings = new PrometheusSettings(ConfigFactory.load("autobind"))
        val extension = new PrometheusExtension(ActorSystem("test-autobind", ConfigFactory.load("autobind")))
        eventually {
          try {
            new Socket(settings.bindInterface, settings.bindPort)
          } catch {
            case NonFatal(e) => fail(s"Expected to be able to connect to ${settings.bindInterface}:${settings.bindPort}, instead got ${e.getClass.getSimpleName}")
          }
        }
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
      |kamon.prometheus.refresh-interval = 1 hour
    """.stripMargin).withFallback(ConfigFactory.load("reference"))

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
