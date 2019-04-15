package io.asterisque.wire.gateway

import java.net.URI

import org.specs2.Specification

import scala.concurrent.ExecutionContext.Implicits.global

abstract class BridgeSpec extends Specification {
  def is =
    s2"""
basic parameter test. $testParameters
"""

  protected def availableBridges:Seq[Bridge]

  /**
    * ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )
    */
  private[this] val URI_SCHEME_SPEC = "[a-zA-Z][a-zA-Z0-9\\+\\-\\.]*".r

  private[this] def testParameters = availableBridges.map { bridge =>
    val conf = Bridge.Config()
    val uri = URI.create("unit-test://hostname:port/")
    bridge.supportedURISchemes.map(_ must beMatching(URI_SCHEME_SPEC)).reduceLeft(_ and _) and
      (bridge.newWire(null, conf) must throwA[NullPointerException]) and
      (bridge.newWire(uri, null) must throwA[NullPointerException]) and
      (bridge.newServer(null, conf, _ => null) must throwA[NullPointerException]) and
      (bridge.newServer(uri, null, _ => null) must throwA[NullPointerException]) and
      (bridge.newServer(uri, conf, null) must throwA[NullPointerException])
  }.reduceLeft(_ and _)
}
