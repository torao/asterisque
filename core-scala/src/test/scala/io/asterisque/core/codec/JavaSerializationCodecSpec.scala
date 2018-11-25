package io.asterisque.core.codec

import java.io.{ByteArrayOutputStream, EOFException, ObjectOutputStream}
import java.nio.{ByteBuffer, ByteOrder}

import io.asterisque.core.msg.Close
import org.specs2.specification.core.SpecStructure

class JavaSerializationCodecSpec extends MessageCodecSpec {
  val codec = new JavaSerializationCodec()

  override def is:SpecStructure = {
    super.is.append(s2"""
  construct with ClassLoader. $constructWithClassLoader
  returns empty if full-message doesn't wsFrameReceived $returnsEmptyIfFullyMessageDoesntRead
  throws CodecException if contains unserializable object. $unserializeObject
  throws CodecException if message binary too large. $messageBinaryTooLong
  throws CodecException if binary is not serialized message $binaryIsNotSerializedMessage
""")
  }

  private[this] def constructWithClassLoader = {
    new JavaSerializationCodec(Thread.currentThread().getContextClassLoader)
    success
  }

  private[this] def unserializeObject = {
    codec.encode(new Close(1.toShort, new Object())) must throwA[CodecException]
  }

  private[this] def messageBinaryTooLong = {
    codec.encode(new Close(0.toShort, new Array[Byte](MessageCodec.MaxMessageSize + 1))) must throwA[CodecException]
  }

  private[this] def returnsEmptyIfFullyMessageDoesntRead = {
    (codec.decode(ByteBuffer.allocate(0)).isPresent must beFalse) and
      (codec.decode(ByteBuffer.wrap(Array[Byte](0, 10, 0))).isPresent must beFalse)
  }

  private[this] def binaryIsNotSerializedMessage = {

    // not a Java serialized binary
    val notASerBinary = codec.decode({
      val notASerObj = ByteBuffer.allocate(100)
      notASerObj.order(ByteOrder.BIG_ENDIAN)
      notASerObj.putShort(3)
      notASerObj.put(Array[Byte](0, 1, 2))
      notASerObj.flip()
      notASerObj
    }) must throwA[CodecException].like {
      case ex => ex.getCause.getClass === classOf[EOFException]
    }

    // java serialized but not a Message
    val notASerMsg = codec.decode({
      val baos = new ByteArrayOutputStream()
      val out = new ObjectOutputStream(baos)
      out.writeObject("hello, world")
      out.close()
      val bin = baos.toByteArray
      val b = ByteBuffer.allocate(2 + bin.length)
      b.putShort(bin.length.toShort).put(bin).flip()
      b
    }) must throwA[CodecException].like {
      case ex => ex.getCause.getClass === classOf[ClassCastException]
    }

    notASerBinary and notASerMsg
  }
}
