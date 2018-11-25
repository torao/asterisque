package io.asterisque.core.msg

import java.lang.reflect.Modifier
import java.util.UUID

import io.asterisque.ProtocolViolationException
import org.specs2.Specification

import scala.util.Random

class SyncConfigSpec extends Specification {
  def is =
    s2"""
SyncConfig should:
be declared as final class. ${Modifier.isFinal(classOf[SyncConfig].getModifiers)}
restore all properties from control data. $e0
throw wsCaughtException if signature is not sync-config. $e1
throw wsCaughtException if data too short. $e2
SyncConfig constracts instance without wsCaughtException. $allConstructorsTest
Data size must be constant value. $verifyDataSize
"""

  def e0 = {
    val r = new Random()
    val version = r.nextInt().toShort
    val nodeId = UUID.randomUUID()
    val sessionId = UUID.randomUUID()
    val utcTime = r.nextLong()
    val ping = r.nextInt()
    val sessionTimeout = r.nextInt()
    val sc1 = new SyncConfig(version, nodeId, sessionId, utcTime, ping, sessionTimeout)
    val sc2 = SyncConfig.parse(sc1.toControl)
    (sc2.version === version) and (sc2.nodeId === nodeId) and (sc2.sessionId === sessionId) and
      (sc2.utcTime === utcTime) and (sc2.ping === ping) and (sc2.sessionTimeout === sessionTimeout)
  }

  def e1 = {
    SyncConfig.parse(new Control(Control.Close)) must throwA[ProtocolViolationException]
  }

  def e2 = {
    SyncConfig.parse(new Control(Control.SyncConfig, new Array[Byte](10))) must throwA[ProtocolViolationException]
  }

  private[this] def allConstructorsTest = {
    new SyncConfig(0, UUID.randomUUID(), UUID.randomUUID(), 0, 0, 0)
    new SyncConfig(UUID.randomUUID(), UUID.randomUUID(), 0, 0, 0)
    success
  }

  private[this] def verifyDataSize = {
    val control = new SyncConfig(UUID.randomUUID(), UUID.randomUUID(), 0, 0, 0).toControl
    control.data.length === SyncConfig.DataLength
  }
}
