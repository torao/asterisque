package io.asterisque.wire.rpc

import io.asterisque.test._
import io.asterisque.utils.Version
import io.asterisque.wire.message.Message.Control.CloseField
import io.asterisque.wire.message.Message.{Block, Close, Control, Open}
import io.asterisque.wire.message.SyncSession
import io.asterisque.wire.rpc.ObjectMapper._
import org.specs2.Specification

import scala.util.Random

class ObjectMapperSpec extends Specification {
  def is =
    s2"""
It can serialize and deserialize all kind of messages. $serializeAndDeserialize
It can serialize and deserialize default types. $encodeAndDecodeDefaultTypes
It must throw CodecException if the serialized message is too large. $encodedMessageTooLarge
It must throw Unsatisfied if decodes too short message. $decodeTooShortMessage
It must throw CodecException if the decode message is broken. $decodeBrokenMessage
It must throw CodecException for undefined type. $encodeUndefinedType
It must throw CodecException for undefined binary. $decodeUndefinedType
Data size must be constant value. $verifyDataSize
"""

  private[this] def serializeAndDeserialize = {
    val r = new Random(7498374)

    def nextShort = r.nextInt().toShort

    def nextByte = r.nextInt().toByte

    Seq(
      Open(nextShort, randomASCII(49823, 64), nextShort, randomByteArray(4981, 256)),
      Close(nextShort, nextByte, randomByteArray(49829, 256)),
      Block(nextShort, (nextByte & 0x7F).toByte, randomByteArray(89342, 256), 0, 256),
      Block.eof(nextShort),
      Control(SyncSession(Version(r.nextInt()), r.nextLong(), Map(
        "ping" -> r.nextInt().toString, "sessionTimeout" -> r.nextInt().toString
      ))),
      Control(CloseField)
    ).map { msg1 =>
      val intermediate = MESSAGE.encode(msg1)
      val msg2 = MESSAGE.decode(intermediate)
      (msg2 === msg1) and (msg2.hashCode() === msg1.hashCode()) and (msg2.equals(null) must beFalse)
    }.reduceLeft(_ and _)
  }

  private[this] def encodedMessageTooLarge = {
    val close = Close(0, 0, randomByteArray(489223, 0xFFFF))
    MESSAGE.encode(close) must throwA[CodecException]
  }

  private[this] def decodeTooShortMessage = {
    Seq[Array[Byte]](
      Array(Msg.Open, 0),
      Array(Msg.Control, 3, 0),
      Array(Msg.Control, 127, 0, Control.SyncSession, 0, 0),
      Array(Msg.Control, 4, 0, Control.SyncSession) // empty binary for SyncSession
    ).map { binary =>
      MESSAGE.decode(binary) must throwA[Unsatisfied]
    }.reduceLeft(_ and _)
  }

  private[this] def decodeBrokenMessage = {
    Seq[Array[Byte]](
      Array(Msg.Open, 0, 0),
      Array((-1).toByte, 3, 0),
      Array(Msg.Control, 4, 0, (-1).toByte)
    ).map { binary =>
      MESSAGE.decode(binary) must throwA[CodecException].like { case ex => ex.isInstanceOf[Unsatisfied] must beFalse }
    }.reduceLeft(_ and _)
  }

  private[this] def encodeAndDecodeDefaultTypes = {
    Seq[Any](
      null,
      true,
      false,
      Byte.MinValue, Byte.MaxValue,
      Short.MinValue, Short.MaxValue,
      Int.MinValue, Int.MaxValue,
      Long.MinValue, Long.MaxValue,
      Float.MinValue, Float.MaxValue,
      Double.MinValue, Double.MaxValue,
      ('A', "A"), ('\n', "\n"), ('\u0000', "\u0000"),
      randomString(938443, 256)
    ).map {
      case (value, expected) =>
        val actual = decode(encode(value))
        actual === expected
      case expected =>
        val actual = decode(encode(expected))
        actual === expected
    }
  }

  private[this] def encodeUndefinedType = {
    Seq[Any](
      new Serializable {}
    ).map { value =>
      encode(value) must throwA[CodecException]
    }.reduceLeft(_ and _)
  }

  private[this] def decodeUndefinedType = {
    Seq[Array[Byte]](
      Array((-1).toByte)
    ).map { value =>
      decode(value) must throwA[CodecException]
    }.reduceLeft(_ and _)
  }

  private[this] def verifyDataSize = {
    val data = ObjectMapper.MESSAGE.encode(Control(SyncSession(0, Map.empty)))
    data.length must lessThan(0xFFFF)
  }

}
