package io.asterisque.core.wire

import io.asterisque.core.wire.Bridge
import org.slf4j.LoggerFactory
import org.specs2.Specification

abstract class BridgeSpec extends Specification {
  def is =
    s2"""
"""

  private val logger = LoggerFactory.getLogger(getClass)

  protected def newBridge():Bridge

}
