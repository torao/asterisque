package io.asterisque.core.msg

import java.lang.reflect.Modifier
import java.util.UUID

import io.asterisque.ProtocolViolationException
import org.specs2.Specification

import scala.util.Random

class SyncSessionSpec extends Specification {
  def is =
    s2"""
SyncSession should:
be declared as final class. ${Modifier.isFinal(classOf[SyncSession].getModifiers)}
restore all properties from control data. $e0
throw wsCaughtException if signature is not sync-config. $e1
throw wsCaughtException if data too short. $e2
SyncSession constracts instance without wsCaughtException. $allConstructorsTest
Data size must be constant value. $verifyDataSize
"""

  def e0 = {
    val r = new Random(7498374)
    val version = r.nextInt().toShort
    val nodeId = UUID.randomUUID()
    val sessionId = UUID.randomUUID()
    val serviceId = r.nextASCIIString(0xFF)
    val utcTime = r.nextLong()
    val ping = r.nextInt()
    val sessionTimeout = r.nextInt()
    val sc1 = new SyncSession(version, nodeId, sessionId, serviceId, utcTime, ping, sessionTimeout)
    val sc2 = SyncSession.parse(sc1.toControl)
    (sc2.version === version) and (sc2.nodeId === nodeId) and (sc2.sessionId === sessionId) and
      (sc2.serviceId === serviceId) and
      (sc2.utcTime === utcTime) and (sc2.ping === ping) and (sc2.sessionTimeout === sessionTimeout)
  }

  def e1 = {
    SyncSession.parse(new Control(Control.Close)) must throwA[ProtocolViolationException]
  }

  def e2 = {
    SyncSession.parse(new Control(Control.SyncSession, new Array[Byte](10))) must throwA[ProtocolViolationException]
  }

  private[this] def allConstructorsTest = {
    new SyncSession(0, UUID.randomUUID(), UUID.randomUUID(), "", 0, 0, 0)
    new SyncSession(UUID.randomUUID(), UUID.randomUUID(), "", 0, 0, 0)
    success
  }

  private[this] def verifyDataSize = {
    val control = new SyncSession(UUID.randomUUID(), UUID.randomUUID(), "", 0, 0, 0).toControl
    control.data.length === SyncSession.MinLength
  }

  implicit class _Random(random:Random) {
    def nextASCIIString(length:Int):String = {
      val s = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
      (0 until length).map(_ => s(random.nextInt(s.length))).mkString
    }
  }

}
