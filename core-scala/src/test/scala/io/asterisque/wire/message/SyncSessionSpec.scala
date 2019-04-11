package io.asterisque.wire.message

import java.lang.reflect.Modifier

import io.asterisque.utils.Version
import io.asterisque.wire.Spec
import io.asterisque.wire.rpc.{CodecException, ObjectMapper}
import org.msgpack.MessagePack
import org.specs2.Specification
import org.specs2.specification.core.SpecStructure

import scala.util.Random

class SyncSessionSpec extends Specification {
  def is:SpecStructure =
    s2"""
It should be declared as final class. ${Modifier.isFinal(classOf[SyncSession].getModifiers)}
It can serialize and deserialize. $serializeAndDeserialize
It should throw exception if service id is too long. $tooLongServiceId
It should throw exception if data binary is broken. $throwExceptionIfBinaryIsBroken
It should throw exception if empty binary. $throwExceptionIfEmptyBinary
SyncSession constracts instance without wsCaughtException. $allConstructorsTest
Data size must be constant value. $verifyDataSize
"""

  private[this] def serializeAndDeserialize = {
    val r = new Random(7498374)
    val version = Version(r.nextInt())
    val serviceId = r.nextASCIIString(0xFF)
    val utcTime = r.nextLong()
    val ping = r.nextInt()
    val sessionTimeout = r.nextInt()
    val config = Map("ping" -> ping.toString, "sessionTimeout" -> sessionTimeout.toString)
    val sc1 = SyncSession(version, serviceId, utcTime, config)
    val packer = new MessagePack().createBufferPacker()
    ObjectMapper.SYNC_SESSION.encode(packer, sc1)
    val sc2 = ObjectMapper.SYNC_SESSION.decode(new MessagePack().createBufferUnpacker(packer.toByteArray))
    (sc1.equals(sc1) must beTrue) and
      (sc1.equals(sc2) must beTrue) and
      (sc1.equals(null) must beFalse) and
      (sc1.hashCode() === sc2.hashCode()) and
      (sc2.version === version) and
      (sc2.serviceId === serviceId) and (sc2.utcTime === utcTime) and
      (sc2.config === config)
  }

  private[this] def tooLongServiceId = {
    val r = new Random(573948)
    SyncSession(
      Version(0),
      r.nextASCIIString(Spec.Std.MAX_SERVICE_ID_BYTES + 1),
      System.currentTimeMillis(),
      Map.empty
    ) must throwA[IllegalArgumentException]
  }

  private[this] def throwExceptionIfBinaryIsBroken = {
    ObjectMapper.SYNC_SESSION.decode(new MessagePack().createBufferUnpacker(Array[Byte](0, 0, 0, 0))) must throwA[CodecException]
  }

  private[this] def throwExceptionIfEmptyBinary = {
    ObjectMapper.SYNC_SESSION.decode(new MessagePack().createBufferUnpacker(Array.empty[Byte])) must throwA[CodecException]
  }

  private[this] def allConstructorsTest = {
    SyncSession(Version(0), "serviceId", 0, Map.empty)
    SyncSession("serviceId", 0, Map.empty)
    success
  }

  private[this] def verifyDataSize = {
    val packer = new MessagePack().createBufferPacker()
    ObjectMapper.SYNC_SESSION.encode(packer, SyncSession("", 0, Map.empty))
    val data = packer.toByteArray
    data.length must lessThan(0xFFFF)
  }

  implicit class _Random(random:Random) {
    def nextASCIIString(length:Int):String = {
      val s = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
      (0 until length).map(_ => s(random.nextInt(s.length))).mkString
    }
  }

}
