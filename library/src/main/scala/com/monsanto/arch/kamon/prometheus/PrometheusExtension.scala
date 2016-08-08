package com.monsanto.arch.kamon.prometheus

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import akka.event.Logging
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import kamon.Kamon
import kamon.metric.TickMetricSnapshotBuffer
import spray.can.Http
import spray.can.Http.{Bind, Bound}
import spray.routing.Route

import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/** A Kamon extension that provides a Spray endpoint so that Prometheus can retrieve metrics from Kamon.
  *
  * TODO: add real documentation
  *
  * @param system the Actor system to which this class acts as an extension
  * @author Daniel Solano Gómez
  */
class PrometheusExtension(system: ActorSystem) extends Kamon.Extension {
  /** Handy log reference. */
  private val log = Logging(system, classOf[PrometheusExtension])
  private val config = system.settings.config

  /** Expose the extension’s settings. */
  val settings: PrometheusSettings = new PrometheusSettings(config)

  /** Returns true if the results from the extension need to be buffered because the refresh less frequently than the
    * tick interval.
    */
  val isBuffered: Boolean = settings.refreshInterval > Kamon.metrics.settings.tickInterval

  /** Manages the Spray endpoint. */
  val endpoint = PrometheusEndpoint(system, settings)
  /** Listens to and records metrics. */
  private[prometheus] val listener = system.actorOf(PrometheusListener.props(endpoint), "prometheus-listener" + UUID.randomUUID().toString)
  /** If the listener needs to listen less frequently than ticks, set up a buffer. */
  private[prometheus] val buffer = {
    if (isBuffered) {
      system.actorOf(TickMetricSnapshotBuffer.props(settings.refreshInterval, listener), "prometheus-buffer" + UUID.randomUUID().toString)
    } else {
      listener
    }
  }

  /** The Spray endpoint. */
  val route: Route = endpoint.route

  log.info("Starting the Kamon(Prometheus) extension")
  settings.subscriptions.foreach { case (category, selections) ⇒
    selections.foreach { selection ⇒
      Kamon.metrics.subscribe(category, selection, buffer, permanently = true)
    }
  }

  if (settings.bindEnabled) {
    implicit val bindTimeout = Timeout(settings.bindTimeout)
    implicit val sys = system
    implicit val ec = system.dispatcher
    log.info(s"Attempting to bind Kamon Prometheus endpoint to ${settings.bindInterface}:${settings.bindPort}")
    val serviceActor = system.actorOf(Props(classOf[PrometheusService], settings, endpoint))
    val bindResult = IO(Http) ? Bind(serviceActor, settings.bindInterface, settings.bindPort)
    bindResult.onComplete {
      case Success(_: Bound) => log.info(s"Successfully bound prometheus metrics API to ${settings.bindInterface}:${settings.bindPort}")
      case Success(other) => log.warning(s"Failed to bind prometheus metrics API to ${settings.bindInterface}:${settings.bindPort}. " +
        s"Expected Bound message, got $other")
      case Failure(NonFatal(e)) => log.error(e, s"Failed to bind to ${settings.bindInterface}:${settings.bindPort} within configured timeout of ${settings.bindTimeout}")
    }
  } else {
    log.warning("Metrics API binding is disabled, Prometheus Metrics API will not be available")
  }
}
