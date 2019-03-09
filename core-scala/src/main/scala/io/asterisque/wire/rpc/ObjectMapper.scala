package io.asterisque.wire.rpc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import java.util.ServiceLoader

import io.asterisque.ProtocolViolationException
import io.asterisque.auth.{Algorithms, Certificate}
import io.asterisque.utils.{Debug, Version}
import io.asterisque.wire.message.Message.{Block, Close, Control, Open}
import io.asterisque.wire.message._
import io.asterisque.wire.{Envelope, Spec}
import javax.annotation.{Nonnull, Nullable}
import org.msgpack.io.EndOfBufferException
import org.msgpack.packer.Packer
import org.msgpack.unpacker.Unpacker
import org.msgpack.{MessagePack, MessageTypeException}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

/**
  * 特定の型に対して直列化と復元を実装します。
  *
  * @tparam T 変換するオブジェクトの型
  */
trait ObjectMapper[T] {

  /**
    * 指定された `Packer` に対してオブジェクトを直列化します。
    *
    * @param packer Packer
    * @param value  直列化するオブジェクト
    */
  @throws[CodecException]
  def encode(@Nonnull packer:Packer, @Nullable value:T):Unit

  /**
    * オブジェクトからバイト配列を直接的に得るためのユーティリティメソッド。
    *
    * @param value バイト配列表現を取得するオブジェクト
    * @return バイト配列
    */
  @throws[CodecException]
  @Nonnull
  def encode(@Nullable value:T):Array[Byte] = {
    val packer = new MessagePack().createBufferPacker()
    encode(packer, value)
    packer.toByteArray
  }

  /**
    * 指定された `unpacker` からオブジェクトを復元します。
    *
    * @param unpacker Unpacker
    * @return 復元したオブジェクト
    * @throws CodecException オブジェクトの復元に失敗した場合
    */
  @Nullable
  @throws[CodecException]
  def decode(@Nonnull unpacker:Unpacker):T

  @Nullable
  @throws[CodecException]
  def decode(@Nonnull binary:Array[Byte]):T = try {
    val unpacker = new MessagePack().createBufferUnpacker(binary)
    decode(unpacker)
  } catch {
    case ex:EndOfBufferException =>
      throw new CodecException(binary.map(x => f"$x%02X").mkString, ex)
  }
}

object ObjectMapper {
  private[this] val logger = LoggerFactory.getLogger(classOf[ObjectMapper[_]])

  private[this] val codecs = ServiceLoader.load(classOf[ObjectMapper[Any]]).iterator().asScala.toSeq
  logger.debug(s"using codec: ${codecs.map(_.getClass.getName).mkString(", ")}")

  @throws[CodecException]
  def encode(@Nullable value:Any):Array[Byte] = {
    val packer = new MessagePack().createBufferPacker()
    encode(packer, value)
    packer.toByteArray
  }

  @throws[CodecException]
  def decode(@Nonnull binary:Array[Byte]):Any = decode(new MessagePack().createBufferUnpacker(binary))

  @throws[CodecException]
  def encode(@Nonnull packer:Packer, @Nullable value:Any):Unit = codecs.find { codec =>
    try {
      codec.encode(packer, value)
      logger.trace("encode success: {} @ {}", value, codec.getClass.getSimpleName)
      true
    } catch {
      case _:CodecException =>
        logger.trace("encode unsupported: {} @ {}", value, codec.getClass.getSimpleName)
        false
      case ex:Throwable =>
        logger.warn(s"unexpected exception: ${Debug.toString(value)}", ex)
        false
    }
  }.orElse {
    throw new CodecException(s"unsupported value type: ${Debug.toString(value)}")
  }

  @throws[CodecException]
  def decode(@Nonnull unpacker:Unpacker):Any = {
    codecs.foreach { codec =>
      try {
        return codec.decode(unpacker)
      } catch {
        case _:CodecException =>
          logger.trace("encode unsupported: {} ", codec.getClass.getSimpleName)
        case ex:Throwable =>
          logger.warn(s"unexpected exception: ${codec.getClass.getSimpleName}", ex)
      }
    }
    throw new CodecException(s"codec unsupported")
  }

  object Msg {
    val Control:Byte = '*'
    val Open:Byte = '('
    val Close:Byte = ')'
    val Block:Byte = '#'
  }

  private[this] val MESSAGE_PACK = new MessagePack()

  object MESSAGE {
    /** message_type:UINT8 + message_length:UINT16 */
    private[this] val Padding = new Array[Byte](3)

    @Nonnull
    def encode(@Nonnull msg:Message):Array[Byte] = {
      val out = new ByteArrayOutputStream()
      out.write(Padding)
      val packer = MESSAGE_PACK.createPacker(out)
      val msgType = msg match {
        case open:Open =>
          packer.write(open.pipeId)
          packer.write(open.priority)
          packer.write(open.functionId)
          packer.write(open.params)
          Msg.Open
        case close:Close =>
          packer.write(close.pipeId)
          packer.write(close.code)
          packer.write(close.result)
          Msg.Close
        case block:Block =>
          assert(block.loss >= 0 && block.loss <= 0x7F)
          assert(block.length <= Block.MaxPayloadSize, f"block payload length too large: ${block.length}%d / ${Block.MaxPayloadSize}%d")
          val bits = ((if(block.eof) 1 << 7 else 0) | (block.loss & 0x7F)).toByte
          packer.write(block.pipeId)
          packer.write(bits)
          packer.write(block.payload, block.offset, block.length)
          Msg.Block
        case control:Control =>
          control.data match {
            case syncSession:SyncSession =>
              out.write(Control.SyncSession)
              SYNC_SESSION.encode(packer, syncSession)
            case Control.CloseField =>
              out.write(Control.Close)
          }
          Msg.Control
      }
      val buffer = out.toByteArray

      // Set message type and length
      if(buffer.length > 0xFFFF) {
        throw new CodecException(f"serialized size too large: ${buffer.length}%,d bytes: $msg")
      }
      val buf = ByteBuffer.wrap(buffer, 0, Padding.length)
      buf.order(Spec.Std.endian)
      buf.put(msgType)
      buf.putShort(buffer.length.toShort)
      buffer
    }

    @throws[CodecException]
    @Nonnull
    def decode(@Nonnull binary:Array[Byte]):Message = {
      assert(binary.length >= Padding.length)

      // Get message type and length
      val buf = ByteBuffer.wrap(binary, 0, Padding.length)
      buf.order(Spec.Std.endian)
      val msgType = buf.get()
      val length = buf.getShort & 0xFFFF
      assert(binary.length == length)

      val unpacker = MESSAGE_PACK.createBufferUnpacker(binary, Padding.length, length - Padding.length)
      try {
        msgType match {
          case Msg.Open =>
            val pipeId = unpacker.readShort()
            val priority = unpacker.readByte()
            val functionId = unpacker.readShort()
            val params = unpacker.readByteArray()
            Open(pipeId, priority, functionId, params)
          case Msg.Close =>
            val pipeId = unpacker.readShort()
            val code = unpacker.readByte()
            val result = unpacker.readByteArray()
            Close(pipeId, code, result)
          case Msg.Block =>
            val pipeId = unpacker.readShort()
            val status = unpacker.readByte()
            val eof = ((1 << 7) & status) != 0
            val loss = (status & 0x7F).toByte
            val payload = unpacker.readByteArray()
            Block(pipeId, loss, payload, 0, payload.length, eof)
          case Msg.Control =>
            if(binary.length <= 1) {
              throw new Unsatisfied()
            }
            binary(1) match {
              case Control.SyncSession =>
                Control(SYNC_SESSION.decode(unpacker))
              case Control.Close =>
                Control(Control.CloseField)
            }
        }
      } catch {
        case ex:Exception =>
          val pos = unpacker.getReadByteCount + Padding.length
          throw new CodecException(f"message restoration failed; type=0x${msgType & 0xFF}%02X ('${msgType.toChar}%s'), length=$length%,d; $pos @ ${Debug.toString(binary)}", ex)
      }
    }
  }

  implicit object SYNC_SESSION extends ObjectMapper[SyncSession] {
    override def encode(packer:Packer, sync:SyncSession):Unit = {
      packer.write(sync.version.version)
      ENVELOPE.encode(packer, sync.sealedCertificate)
      packer.write(sync.serviceId)
        .write(sync.utcTime)
      MAP_STRING.encode(packer, sync.config)
    }

    override def decode(unpacker:Unpacker):SyncSession = try {
      val version = Version(unpacker.readInt())
      val envelope = ENVELOPE.decode(unpacker)
      val serviceId = unpacker.readString()
      val utcTime = unpacker.readLong()
      val attr = MAP_STRING.decode(unpacker)
      SyncSession(version, envelope, serviceId, utcTime, attr)
    } catch {
      case ex:EndOfBufferException => throw new Unsatisfied()
      case ex:MessageTypeException =>
        throw new CodecException(s"broken message", ex)
    }
  }

  implicit object CERTIFICATE extends ObjectMapper[Certificate] {
    override def encode(packer:Packer, cert:Certificate):Unit = {
      X509CERTIFICATE.encode(packer, cert.cert)
      MAP_STRING.encode(packer, cert.attrs)
    }

    override def decode(unpacker:Unpacker):Certificate = {
      Certificate(X509CERTIFICATE.decode(unpacker), MAP_STRING.decode(unpacker))
    }
  }

  implicit object X509CERTIFICATE extends ObjectMapper[X509Certificate] {

    import io.asterisque.carillon._

    override def encode(packer:Packer, cert:X509Certificate):Unit = {
      packer.write(cert.toByteArray)
    }

    override def decode(unpacker:Unpacker):X509Certificate = {
      Algorithms.Certificate.load(unpacker.readByteArray())
    }
  }

  implicit object ENVELOPE extends ObjectMapper[Envelope] {
    override def encode(packer:Packer, seal:Envelope):Unit = {
      packer
        .write(seal.payload)
        .write(seal.signType.id)
        .write(seal.sign)
      X509CERTIFICATE.encode(packer, seal.signer)
    }

    override def decode(unpacker:Unpacker):Envelope = {
      val payload = unpacker.readByteArray()
      Envelope.Type.valueOf(unpacker.readByte()) match {
        case Some(sigType) =>
          val signature = unpacker.readByteArray()
          val signer = X509CERTIFICATE.decode(unpacker)
          new Envelope(payload, sigType, signature, signer)
        case None =>
          throw new ProtocolViolationException(s"invalid seal type: $unpacker")
      }
    }
  }

  implicit object MAP_STRING extends ObjectMapper[Map[String, String]] {
    override def encode(packer:Packer, map:Map[String, String]):Unit = {
      packer.writeMapBegin(map.size)
      map.foreach { case (key, value) =>
        packer.write(key)
        packer.write(value)
      }
      packer.writeMapEnd(true)
    }

    override def decode(unpacker:Unpacker):Map[String, String] = {
      val size = unpacker.readMapBegin()
      val map = (0 until size).map { _ =>
        (unpacker.readString(), unpacker.readString())
      }.toMap
      unpacker.readMapEnd(true)
      map
    }
  }

  /**
    * MessagePack の map family は要素数を固定しているが asterisque では複合型 (type + data) をとして保存するため
    * MessagePack の map フォーマットは適用できない。
    *
    * @param packer packer
    * @param map    シリアライズする Map
    */
  private[rpc] def writeMap(packer:Packer, map:scala.collection.Map[_, _]):Unit = {
    assert(map.size <= 0xFFFF)
    packer.write(map.size.toShort)
    map.foreach { case (key, value) =>
      ObjectMapper.encode(packer, key)
      ObjectMapper.encode(packer, value)
    }
  }

  /**
    * [[writeMap()]] でシリアライズしたマップを復元する。
    *
    * @param unpacker unpacker
    * @return 復元したマップ
    */
  private[rpc] def readMap(unpacker:Unpacker):Map[_, _] = {
    val size = unpacker.readShort() & 0xFFFF
    (0 until size).map { _ =>
      val key = ObjectMapper.decode(unpacker)
      val value = ObjectMapper.decode(unpacker)
      (key, value)
    }.toMap
  }

  /**
    * MessagePack の array family は要素数を固定しているが asterisque では複合型 (type + data) をとして保存するため
    * MessagePack の array フォーマットは適用できない。
    *
    * @param packer packer
    * @param array  シリアライズする Array
    */
  private[rpc] def writeArray(packer:Packer, array:Iterable[_]):Unit = {
    assert(array.size <= 0xFFFF)
    packer.write(array.size.toShort)
    array.foreach(value => ObjectMapper.encode(packer, value))
  }

  /**
    * [[writeArray()]] でシリアライズしたアレイを復元する。
    *
    * @param unpacker unpacker
    * @return 復元したアレイ
    */
  private[rpc] def readArray(unpacker:Unpacker):Iterable[Any] = {
    val size = unpacker.readShort() & 0xFFFF
    (0 until size).map(_ => ObjectMapper.decode(unpacker))
  }

}
