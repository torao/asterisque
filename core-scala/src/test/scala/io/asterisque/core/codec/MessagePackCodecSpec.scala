package io.asterisque.core.codec

import java.nio.ByteBuffer

import org.specs2.specification.core.SpecStructure

class MessagePackCodecSpec extends MessageCodecSpec {
  val codec:MessageCodec = MessageCodec.MessagePackCodec

  override def is:SpecStructure = {
    super.is.append(s2"""
  return empty if message is not fully read. $returnsEmptyIfMessageIsNotSatisfied
""")
  }

  private[this] def returnsEmptyIfMessageIsNotSatisfied = {
    val codec = new MsgPackCodec()
    (codec.newUnmarshal(b()).readInt8() must throwA[Unsatisfied]) and
      (codec.newUnmarshal(b()).readInt16() must throwA[Unsatisfied]) and
      (codec.newUnmarshal(b()).readInt32() must throwA[Unsatisfied]) and
      (codec.newUnmarshal(b()).readInt64() must throwA[Unsatisfied]) and
      (codec.newUnmarshal(b()).readFloat32() must throwA[Unsatisfied]) and
      (codec.newUnmarshal(b()).readFloat64() must throwA[Unsatisfied])
  }

  private[this] def b(bytes:Byte*):ByteBuffer = ByteBuffer.wrap(bytes.toArray)
}
