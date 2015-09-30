package com.monsanto.arch.kamon.prometheus.demo

import java.net.InetAddress

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import com.monsanto.arch.kamon.spray.can.KamonHttp
import com.typesafe.config.ConfigFactory
import kamon.Kamon
import spray.can.Http
import spray.http.Uri

import scala.concurrent.duration.DurationInt

/** A simple demo application that generates metrics that can be scraped by Prometheus.
  *
  * @author Daniel Solano Gómez
  */
object Demo extends App {
  // Starting Kamon is the first thing that must be done
  Kamon.start()
  // load settings
  val settings = new DemoSettings(ConfigFactory.load())

  // an actor system for running the demo
  implicit val system = ActorSystem("demo")

  // needed by the Spray Can server
  implicit val timeout = Timeout(5.seconds)

  // create the demo service
  val service = system.actorOf(Props[DemoServiceActor], "demo-service")

  import system.dispatcher

  // start listening
  val bound = IO(KamonHttp) ? Http.Bind(service, settings.hostname, settings.port)

  // once bound, start tasks
  bound.onSuccess { case Http.Bound(interface) ⇒
    val baseUri = {
      val host = if (interface.getAddress.isAnyLocalAddress) {
        Uri.Host(InetAddress.getLocalHost.getHostName)
      } else {
        Uri.Host(interface.getAddress.getHostName)
      }
      Uri(Uri.httpScheme(), new Uri.Authority(host, interface.getPort))
    }

    system.actorOf(LoadGenerator.props(baseUri).withDispatcher("my-fork-join-dispatcher"), "load-generator-1")
    system.actorOf(LoadGenerator.props(baseUri).withDispatcher("my-thread-pool-dispatcher"), "load-generator-2")
    // start a gauge that measures random values
    Kamon.metrics.gauge("basic-gauge") {
      val gen: () ⇒ Long = {() ⇒
        (math.pow(10.0, math.random * 2.74116) + 98.12 + (math.random * 500 - 250)).toLong.max(0L)
      }
      gen
    }
  }
}
