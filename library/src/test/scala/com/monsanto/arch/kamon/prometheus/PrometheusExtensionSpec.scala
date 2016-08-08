package com.monsanto.arch.kamon.prometheus

import com.monsanto.arch.kamon.prometheus.converter.SnapshotConverter
import com.monsanto.arch.kamon.prometheus.metric._
import com.typesafe.config.ConfigFactory
import kamon.Kamon
import kamon.metric.SingleInstrumentEntityRecorder
import kamon.util.MilliTimestamp
import org.scalacheck.Gen
import org.scalactic.Uniformity
import org.scalatest.concurrent.Eventually
import org.scalatest.{DoNotDiscover, Matchers, WordSpec}
import spray.http.HttpHeaders.{Accept, `Accept-Encoding`}
import spray.http.{HttpEncodings, HttpResponse, MediaType, StatusCodes}
import spray.httpx.encoding.Gzip
import spray.httpx.unmarshalling.{Deserialized, FromResponseUnmarshaller, Unmarshaller}
import spray.routing.RoutingSettings
import spray.testkit.ScalatestRouteTest

import scala.collection.immutable.ListMap
import scala.concurrent.duration._

/** Tests the end-to-end functionality of the
  * [[com.monsanto.arch.kamon.prometheus.PrometheusExtension PrometheusExtension]].
  *
  * @author Daniel Solano Gómez
  */
@DoNotDiscover
class PrometheusExtensionSpec extends WordSpec with KamonTestKit with ScalatestRouteTest with Matchers with Eventually {
  /** Unmarshaller from the text format. */
  val textUnmarshaller =
    Unmarshaller.delegate[String, Seq[MetricFamily]](PrometheusEndpoint.TextMediaType)(TextFormat.parse)
  /** Unmarshaller from the protocol buffer format. */
  val protoBufUnmarshaller =
    Unmarshaller.delegate[Array[Byte], Seq[MetricFamily]](PrometheusEndpoint.ProtoBufMediaType)(ProtoBufFormat.parse)
  /** Unmarshaller that supports both formats. */
  val plainUnmarshaller = Unmarshaller.oneOf(textUnmarshaller, protoBufUnmarshaller)
  /** Unmarshaller that supports both formats, including the gzipped versions. */
  implicit val mainUnmarshaller = new FromResponseUnmarshaller[Seq[MetricFamily]] {
    override def apply(response: HttpResponse): Deserialized[Seq[MetricFamily]] = {
      response.encoding match {
        case HttpEncodings.identity ⇒ plainUnmarshaller(response.entity)
        case HttpEncodings.gzip ⇒ plainUnmarshaller(Gzip.decode(response).entity)
      }
    }
  }

  override def cleanUp = {}

  override implicit val system = kamonActorSystem
  val settings: PrometheusSettings = new PrometheusSettings(ConfigFactory.load())
  implicit def actorRefFactory = kamonActorSystem

  implicit lazy val testTimeout = RouteTestTimeout(3.seconds)
  implicit lazy val routeSettings = RoutingSettings.default(kamonActorSystem)
  lazy val extension = new PrometheusExtension(kamonActorSystem)
  lazy val route = extension.route

  "The Prometheus extension endpoint" when {
    "it has no snapshots" should {
      "returns an empty response" in {
        Get("/metrics") ~> route ~> check {
          handled shouldBe true
          status shouldBe StatusCodes.NoContent
        }
      }
    }

    "doing a plain-text request" when {
      def doGet() = Get("/metrics") ~> route

      "it has content" should {
        "handle GET requests" in new WithData {
          doGet() ~> check {
            handled shouldBe true
            status shouldBe StatusCodes.OK
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }

        "use the correct encoding" in new WithData {
          doGet() ~> check {
            definedCharset shouldBe defined
            charset.value shouldBe "UTF-8"
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }

        "use the correct media type" in new WithData {
          doGet() ~> check {
            mediaType shouldBe MediaType.custom("text", "plain", parameters = Map("version" -> "0.0.4"))
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }

        "is not compressed" in new WithData {
          doGet() ~> check {
            response.encoding shouldBe HttpEncodings.identity
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }

        "have the correct content" in new WithData {
          doGet() ~> check {
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }
      }
    }

    "doing a plain-text request accepting gzip compression" when {
      def doGet() = Get("/metrics") ~> `Accept-Encoding`(HttpEncodings.gzip) ~> route

      "it has content" should {
        "handle GET requests" in new WithData {
          doGet() ~> check {
            handled shouldBe true
            status shouldBe StatusCodes.OK
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }

        "use the correct encoding" in new WithData {
          doGet() ~> check {
            definedCharset shouldBe defined
            charset.value shouldBe "UTF-8"
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }

        "use the correct media type" in new WithData {
          doGet() ~> check {
            mediaType shouldBe MediaType.custom("text", "plain", parameters = Map("version" -> "0.0.4"))
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }

        "is compressed" in new WithData {
          doGet() ~> check {
            response.encoding shouldBe HttpEncodings.gzip
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }

        "have the correct content" in new WithData {
          doGet() ~> check {
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }
      }
    }

    "doing a protocol buffer request" when {
      def doGet() = Get("/metrics") ~> Accept(PrometheusEndpoint.ProtoBufMediaType) ~> route

      "it has content" should {
        "handle GET requests" in new WithData {
          doGet() ~> check {
            handled shouldBe true
            status shouldBe StatusCodes.OK
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }

        "use the correct media type" in new WithData {
          doGet() ~> check {
            mediaType shouldBe PrometheusEndpoint.ProtoBufMediaType
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }

        "is not compressed" in new WithData {
          doGet() ~> check {
            response.encoding shouldBe HttpEncodings.identity
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }

        "have the correct content" in new WithData {
          doGet() ~> check {
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }
      }
    }

    "doing a protocol buffer request accepting gzip compression" when {
      def doGet() = Get("/metrics") ~>
        `Accept-Encoding`(HttpEncodings.gzip) ~>
        Accept(PrometheusEndpoint.ProtoBufMediaType) ~>
        route

      "it has content" should {
        "handle GET requests" in new WithData {
          doGet() ~> check {
            handled shouldBe true
            status shouldBe StatusCodes.OK
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }

        "use the correct media type" in new WithData {
          doGet() ~> check {
            mediaType shouldBe PrometheusEndpoint.ProtoBufMediaType
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }

        "is not compressed" in new WithData {
          doGet() ~> check {
            response.encoding shouldBe HttpEncodings.gzip
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }

        "have the correct content" in new WithData {
          doGet() ~> check {
            checkNormalisedResponse(responseAs[Seq[MetricFamily]], snapshot)
          }
        }
      }
    }
  }

  /** Normalises a metric family by ensuring its metrics are given an order and their timestamps are all given the
    * same value.
    */
  val normalised = new Uniformity[MetricFamily] {
    /** Sorts metrics according to their labels.  Assumes the labels are sorted. */
    def metricSort(a: Metric, b: Metric): Boolean = {
      (a.labels.headOption, b.labels.headOption) match {
        case (Some(x), Some(y)) ⇒
          if (x._1 < y._1) {
            true
          } else if (x._1 == y._1) {
            x._2 < y._2
          } else {
            false
          }
        case (None, Some(_)) ⇒ true
        case (Some(_), None) ⇒ false
        case (None, None) ⇒ false
      }
    }

    override def normalizedOrSame(b: Any): Any = b match {
      case mf: MetricFamily ⇒ normalized(mf)
      case _ ⇒ b
    }
    //noinspection ComparingUnrelatedTypes
    override def normalizedCanHandle(b: Any): Boolean = b.isInstanceOf[MetricFamily]

    override def normalized(metricFamily: MetricFamily): MetricFamily = {
      val normalMetrics = metricFamily.metrics.map { m ⇒
        val sortedLabels = ListMap(m.labels.toSeq.sorted: _*)
        Metric(m.value, new MilliTimestamp(0), sortedLabels)
      }.sortWith(metricSort)
      MetricFamily(metricFamily.name, metricFamily.prometheusType, metricFamily.help, normalMetrics)
    }
  }

  def checkNormalisedResponse(response: Seq[MetricFamily], snapshot: Seq[MetricFamily]) = {
    val normalizedResponse = response.map(normalised.normalized)
    val normalizedSnapshot = snapshot.map(normalised.normalized)
    normalizedSnapshot.foreach { shot =>
      val actual = normalizedResponse.find(_.name == shot.name)
        .getOrElse(fail(s"Expected to get a response containing metric with name ${shot.name}"))
      actual.metrics.toSet shouldBe shot.metrics.toSet
    }
  }

  trait WithData {
    import SnapshotConverter.{KamonCategoryLabel, KamonNameLabel}
    val ts = MilliTimestamp.now
    import MetricValue.{Bucket => B, Histogram => HG}
    val ∞ = Double.PositiveInfinity
    val randomCounterName1 = Gen.identifier.sample.getOrElse(fail("Failed to generate a valid metric name"))
    val randomCounterName2 = Gen.identifier.sample.getOrElse(fail("Failed to generate a valid metric name"))
    val randomHistoName1 = Gen.identifier.sample.getOrElse(fail("Failed to generate a valid metric name"))
    val randomHistoName2 = Gen.identifier.sample.getOrElse(fail("Failed to generate a valid metric name"))
    val randomMinMaxName = Gen.identifier.sample.getOrElse(fail("Failed to generate a valid metric name"))


    val snapshot = Seq(
      MetricFamily(randomCounterName1, PrometheusType.Counter, None,
        Seq(
          Metric(MetricValue.Counter(1), ts,
            Map("type" → "a",
              KamonCategoryLabel → SingleInstrumentEntityRecorder.Counter,
              KamonNameLabel → randomCounterName1)),
          Metric(MetricValue.Counter(2), ts,
            Map("type" → "b",
              KamonCategoryLabel → SingleInstrumentEntityRecorder.Counter,
              KamonNameLabel → randomCounterName1)))),
      MetricFamily(randomCounterName2, PrometheusType.Counter, None,
        Seq(Metric(MetricValue.Counter(42), ts,
          Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.Counter, KamonNameLabel → randomCounterName2)))),
      MetricFamily(randomHistoName1, PrometheusType.Histogram, None,
        Seq(
          Metric(HG(Seq(B(1, 20), B(4, 23), B(∞, 23)), 23, 32), ts,
            Map("got_label" → "yes",
              KamonCategoryLabel → SingleInstrumentEntityRecorder.Histogram,
              KamonNameLabel → randomHistoName1)),
          Metric(HG(Seq(B(3, 2), B(5, 6), B(∞, 6)), 6, 26), ts,
            Map("got_label" → "true",
              KamonCategoryLabel → SingleInstrumentEntityRecorder.Histogram,
              KamonNameLabel → randomHistoName1)))),
      MetricFamily(randomHistoName2, PrometheusType.Histogram, None,
        Seq(Metric(HG(Seq(B(20, 20), B(∞, 20)), 20, 400), ts,
          Map(KamonCategoryLabel → SingleInstrumentEntityRecorder.Histogram, KamonNameLabel → randomHistoName2)))),
      MetricFamily(randomMinMaxName, PrometheusType.Histogram, None,
        Seq(Metric(HG(Seq(B(0, 1), B(1, 2), B(3, 3), B(∞, 3)), 3, 4), ts,
          Map(
            KamonCategoryLabel → SingleInstrumentEntityRecorder.MinMaxCounter,
            KamonNameLabel → randomMinMaxName)))))

    Kamon.metrics.counter(randomCounterName1, Map("type" -> "a")).increment()
    Kamon.metrics.counter(randomCounterName1, Map("type" -> "b")).increment(2)
    Kamon.metrics.counter(randomCounterName2).increment(42)
    val h = Kamon.metrics.histogram(randomHistoName1, Map("got_label" → "true"))
    h.record(3, 2)
    h.record(5, 4)
    val h2 = Kamon.metrics.histogram(randomHistoName1, Map("got_label" → "yes"))
    h2.record(1, 20)
    h2.record(4, 3)
    val h3 = Kamon.metrics.histogram(randomHistoName2)
    h3.record(20, 20)
    val mmc = Kamon.metrics.minMaxCounter(randomMinMaxName)
    mmc.increment(3)
    mmc.decrement(2)
    flushSubscriptions()
  }
}
