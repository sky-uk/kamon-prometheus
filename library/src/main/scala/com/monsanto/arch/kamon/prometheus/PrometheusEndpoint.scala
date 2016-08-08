package com.monsanto.arch.kamon.prometheus

import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference

import akka.actor.Actor.Receive
import akka.actor.{ActorRefFactory, ActorSystem}
import akka.io.IO
import com.monsanto.arch.kamon.prometheus.converter.SnapshotConverter
import com.monsanto.arch.kamon.prometheus.metric.{MetricFamily, ProtoBufFormat, TextFormat}
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import spray.can.Http
import spray.can.Http.Bind
import spray.http._
import spray.httpx.marshalling.ToResponseMarshaller
import spray.routing.{Directives, HttpService, HttpServiceActor, Route}

/** Manages the Spray endpoint that Prometheus can use to scrape metrics.
  *
  * @author Daniel Solano Gómez
  */
trait PrometheusEndpoint extends HttpService {
  import PrometheusEndpoint.{ProtoBufContentType, TextContentType}
  val settings: PrometheusSettings
  /** Converts snapshots from Kamon’s native type to the one used by this extension. */
  private lazy val snapshotConverter = new SnapshotConverter(settings)
  /** Mutable cell with the latest snapshot. */
  private lazy val snapshot = new AtomicReference[Seq[MetricFamily]]

  /** Marshals a snapshot to the text exposition format. */
  private lazy val textMarshaller: ToResponseMarshaller[Seq[MetricFamily]] =
    ToResponseMarshaller.delegate[Seq[MetricFamily], String](TextContentType)(TextFormat.format _)

  /** Marshals a snapshot to the protocol buffer exposition format. */
  private lazy val protoBufMarshaller: ToResponseMarshaller[Seq[MetricFamily]] =
    ToResponseMarshaller.delegate[Seq[MetricFamily], Array[Byte]](ProtoBufContentType)(ProtoBufFormat.format _)

  /** Marshals a snapshot depending on content negotiation. */
  private implicit lazy val marshaller: ToResponseMarshaller[Seq[MetricFamily]] =
    ToResponseMarshaller.oneOf(TextContentType, ProtoBufContentType)(textMarshaller, protoBufMarshaller)

  /** Provides a basic route that responds to GET requests with the metrics in a Prometheus-compatible exposition
    * format.  Note that if there is no snapshot information available, this will respond with a No Content
    * response.
    */
  lazy val route: Route = path("metrics"){
    get {
      compressResponseIfRequested() {
        dynamic {
          Option(snapshot.get) match {
            case Some(s) => complete(s)
            case None => complete(StatusCodes.NoContent)
          }
        }
      }
    }
  }

  /** Updates the endpoint's current snapshot atomically. */
  def updateSnapShot(newSnapshot: TickMetricSnapshot): Unit = {
    snapshot.set(snapshotConverter(newSnapshot))
  }
}

object PrometheusEndpoint {
  /** The media type for the Prometheus text-based exposition format. */
  val TextMediaType = MediaTypes.register(
    MediaType.custom("text", "plain", compressible = true, parameters = Map("version" -> "0.0.4")))
  /** Spray version of the canonical UTF-8 charset. */
  val Utf8Charset = {
    val nioCharset = Charset.forName("UTF-8")
    val aliases = nioCharset.aliases().toArray(Array.empty[String])
    HttpCharset.custom(nioCharset.name, aliases: _*).get
  }
  /** Additionally, the Prometheus text-based exposition format requires UTF-8 encoding. */
  val TextContentType = TextMediaType.withCharset(PrometheusEndpoint.Utf8Charset)

  /** Media type for the protocol buffer encoding supported by Prometheus. */
  val ProtoBufMediaType = MediaTypes.register(
    MediaType.custom("application", "vnd.google.protobuf", binary = true, compressible = true,
      parameters = Map("proto" → "io.prometheus.client.MetricFamily", "encoding" → "delimited")))

    /** Content type for the protocol buffer encoding supported by Prometheus.  This exists primarily to match against
      * it during marshalling.
      */
  val ProtoBufContentType = ContentType(ProtoBufMediaType)

  def apply(sys: ActorSystem, conf: PrometheusSettings) = new PrometheusEndpoint {
    override lazy val settings: PrometheusSettings = conf
    override implicit def actorRefFactory: ActorRefFactory = sys
  }
}

class PrometheusService(val settings: PrometheusSettings, val endpoint: PrometheusEndpoint) extends HttpServiceActor {
  override def receive: Receive = runRoute(endpoint.route)
}
