package com.monsanto.arch.kamon.prometheus

import com.monsanto.arch.kamon.prometheus.converter.SnapshotConverterSpec
import com.monsanto.arch.kamon.prometheus.metric.{MetricValueSpec, ProtoBufFormatSpec, TextFormatSpec}
import kamon.Kamon
import org.scalatest.{BeforeAndAfterAll, Suites}

class MasterSuite extends Suites(
  new PrometheusExtensionSpec, new SnapshotConverterSpec) with BeforeAndAfterAll {

  override def beforeAll() = Kamon.start()
  override def afterAll() = Kamon.shutdown()

}
