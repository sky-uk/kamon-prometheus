package com.monsanto.arch.kamon.prometheus

import akka.actor.ActorSystem
import com.monsanto.arch.kamon.prometheus.KamonTestKit.TestCurrentValueCollector
import com.typesafe.config.{Config, ConfigFactory}
import kamon.Kamon
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric.instrument.{CollectionContext, Gauge, UnitOfMeasurement}
import kamon.metric.{Entity, SingleInstrumentEntityRecorder, SubscriptionsDispatcher}
import kamon.util.{LazyActorRef, MilliTimestamp}
import org.scalatest.Suite

/** Utilities for testing with Kamon.
  *
  * @author Daniel Solano Gómez
  */
trait KamonTestKit extends Suite {
  /** The start time of generated snapshots. */
  val start = MilliTimestamp.now
  /** The end time of generated snapshots. */
  val end = new MilliTimestamp(start.millis + 60000)

  /** Takes a snapshot of the given entity and returns it. */
  def snapshotOf(entities: Entity*): TickMetricSnapshot = {
    val collectionContext = CollectionContext(entities.size * 10000)
    val snapshots = entities.map {entity ⇒
      val recorder = Kamon.metrics.find(entity)
      assert(recorder.isDefined)
      val snapshot = recorder.get.collect(collectionContext)
      entity → snapshot
    }.toMap
    TickMetricSnapshot(start, end, snapshots)
  }

  /** Creates a new counter and increments it by the given count.  Returns the entity for the counter. */
  def counter(name: String, count: Long, tags: Map[String,String] = Map.empty,
              unitOfMeasurement: UnitOfMeasurement = UnitOfMeasurement.Unknown): Entity = {
    val c = Kamon.metrics.counter(name, tags, unitOfMeasurement)
    c.increment(count)
    Entity(name, SingleInstrumentEntityRecorder.Counter, tags)
  }

  def counter(count: Long, unitOfMeasurement: UnitOfMeasurement): Entity = {
    val randomName = PrometheusGen.metricName.sample.getOrElse(fail("Failed to generate a valid metric name"))
    counter(randomName, count, Map.empty, unitOfMeasurement)
  }

  /** Creates a new histogram and records the given values.  Returns the entity for the histogram. */
  def histogram(name: String, values: Seq[Long] = Seq.empty, tags: Map[String,String] = Map.empty,
                 unitOfMeasurement: UnitOfMeasurement = UnitOfMeasurement.Unknown): Entity = {
    val h = Kamon.metrics.histogram(name, tags, unitOfMeasurement)
    values.foreach(h.record)

    Entity(name, SingleInstrumentEntityRecorder.Histogram, tags)
  }

  def histogram(unitOfMeasurement: UnitOfMeasurement): Entity = {
    val randomName = PrometheusGen.metricName.sample.getOrElse(fail("Failed to generate a valid metric name"))
    histogram(randomName, Seq.empty, Map.empty, unitOfMeasurement)

  }

  /** Creates a new min-max counter and applies the given increments/decrements.  After every 5 changes, the counter
    * is refreshed.  Returns the entity of the counter. */
  def minMaxCounter(name: String, changes: Seq[Long] = Seq.empty, tags: Map[String,String] = Map.empty,
                     unitOfMeasurement: UnitOfMeasurement = UnitOfMeasurement.Unknown): Entity = {
    val minMaxCounter = Kamon.metrics.minMaxCounter(name, tags, unitOfMeasurement)
    changes.grouped(5).foreach { changesChunk ⇒
      changesChunk.foreach(minMaxCounter.increment)
      minMaxCounter.refreshValues()
    }
    Entity(name, SingleInstrumentEntityRecorder.MinMaxCounter, tags)
  }

  def minMaxCounter(unitOfMeasurement: UnitOfMeasurement): Entity = {
    val randomName = PrometheusGen.metricName.sample.getOrElse(fail("Failed to generate a valid metric name"))
    minMaxCounter(randomName, Seq.empty, Map.empty, unitOfMeasurement)
  }

  /** Creates a new gauge that will record the given values.  Returns the entity of the gauge. */
  def gauge(name: String, readings: Seq[Long] = Seq.empty, tags: Map[String,String] = Map.empty,
             unitOfMeasurement: UnitOfMeasurement = UnitOfMeasurement.Unknown): Entity = {
    val gauge = Kamon.metrics.gauge(name, tags, unitOfMeasurement)(new TestCurrentValueCollector(readings))
    readings.foreach(_ ⇒ gauge.refreshValue())
    Entity(name, SingleInstrumentEntityRecorder.Gauge, tags)
  }

  def gauge(unitOfMeasurement: UnitOfMeasurement): Entity = {
    val randomName = PrometheusGen.metricName.sample.getOrElse(fail("Failed to generate a valid metric name"))
    gauge(randomName, Seq.empty, Map.empty, unitOfMeasurement)
  }

  /** Forcibly flushes all subscriptions.
    *
    * Taken from https://github.com/kamon-io/Kamon/blob/57dd25e3afbfc2682b81c07161850104f32fd841/kamon-core/src/test/scala/kamon/testkit/BaseKamonSpec.scala#L54
    */
  def flushSubscriptions(): Unit = {
    val subscriptionsField = Kamon.metrics.getClass.getDeclaredField("_subscriptions")
    subscriptionsField.setAccessible(true)
    val subscriptions = subscriptionsField.get(Kamon.metrics).asInstanceOf[LazyActorRef]
    subscriptions.tell(SubscriptionsDispatcher.Tick)
  }

  lazy val kamonActorSystem: ActorSystem = {
    Kamon.start()
    val ru = scala.reflect.runtime.universe
    val mirror = ru.runtimeMirror(getClass.getClassLoader)
    val moduleSymbol = ru.typeOf[Kamon.type].termSymbol.asModule
    val moduleMirror = mirror.reflectModule(moduleSymbol)
    val instanceMirror = mirror.reflect(moduleMirror.instance)
    val systemTerm = ru.typeOf[Kamon.type].decl(ru.newTermName("_system")).asTerm.accessed.asTerm
    val fieldMirror = instanceMirror.reflectField(systemTerm)
    fieldMirror.get.asInstanceOf[ActorSystem]
  }

}

object KamonTestKit {
  /** A test configuration that helps in a number of ways:
    *
    * 1. It sets an absurdly long tick interval, allowing the tests to control when ticks occur.
    * 2. Makes the tests less chatty log-wise
    */
  val TestConfig = ConfigFactory.parseString(
    """kamon {
      |  metric {
      |    tick-interval = 1 hour
      |    default-instrument-settings {
      |      gauge.refresh-interval = 1 hour
      |      min-max-counter.refresh-interval = 1 hour
      |    }
      |  }
      |
      |  prometheus.refresh-interval = 1 hour
      |
      |  internal-config.akka {
      |    loglevel = "OFF"
      |    stdout-loglevel = "OFF"
      |    log-dead-letters = off
      |
      |    actor.default-dispatcher {
      |      type = "akka.testkit.CallingThreadDispatcherConfigurator"
      |    }
      |  }
      |}
    """.stripMargin)

  /** Gauge current value recorder implementation that will provide all of the values in a list and then repeat the
    * last value.  The default value is zero.
    */
  class TestCurrentValueCollector(values: Seq[Long]) extends Gauge.CurrentValueCollector {
    val iterator = values.iterator
    var lastValue = 0L

    override def currentValue: Long = {
      if (iterator.hasNext) {
        lastValue = iterator.next()
      }
      lastValue
    }
  }
}
