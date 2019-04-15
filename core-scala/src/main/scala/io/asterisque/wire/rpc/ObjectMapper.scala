package io.asterisque.wire.rpc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.ServiceLoader

import io.asterisque.utils.{Debug, Version}
import io.asterisque.wire.Spec
import io.asterisque.wire.message.Message.{Block, Close, Control, Open}
import io.asterisque.wire.message._
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
    * @throws CodecException オブジェクトの直列化がサポートされていない場合
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
      case ex:Exception =>
        logger.warn(s"unexpected exception for: ${Debug.toString(value)}", ex)
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
        case ex:Exception =>
          logger.warn(s"unexpected exception from: ${codec.getClass.getSimpleName}.decode()", ex)
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
          packer.write(open.serviceId)
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
      if(binary.length < Padding.length) {
        throw new Unsatisfied()
      }

      // Get message type and length
      val buf = ByteBuffer.wrap(binary, 0, Padding.length)
      buf.order(Spec.Std.endian)
      val msgType = buf.get()
      val length = buf.getShort & 0xFFFF
      if(length < Padding.length) {
        throw new CodecException(f"binary length $length%,d too short")
      }
      if(binary.length < length) {
        throw new Unsatisfied(f"binary length ${binary.length}%,d must equal to $length%,d")
      }

      var offset = Padding.length
      var unpacker = MESSAGE_PACK.createBufferUnpacker(binary, offset, length - offset)
      try {
        msgType match {
          case Msg.Open =>
            val pipeId = unpacker.readShort()
            val priority = unpacker.readByte()
            val serviceId = unpacker.readString()
            val functionId = unpacker.readShort()
            val params = unpacker.readByteArray()
            Open(pipeId, priority, serviceId, functionId, params)
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
            if(binary.length <= Padding.length) {
              throw new Unsatisfied()
            }
            offset = Padding.length + 1
            unpacker = MESSAGE_PACK.createBufferUnpacker(binary, offset, length - offset)
            binary(offset - 1) match {
              case Control.SyncSession =>
                Control(SYNC_SESSION.decode(unpacker))
              case Control.Close =>
                Control(Control.CloseField)
              case unexpected =>
                val msg = f"unexpected control code: 0x$unexpected%02X (${Debug.toString(unexpected.toChar)})"
                throw new CodecException(msg)
            }
          case unexpected =>
            val msg = f"unexpected message id: 0x$unexpected%02X (${Debug.toString(unexpected.toChar)})"
            throw new CodecException(msg)
        }
      } catch {
        case ex:Unsatisfied =>
          throw ex
        case _:EndOfBufferException =>
          throw new Unsatisfied()
        case ex:MessageTypeException =>
          throw new CodecException(s"broken message", ex)
        case ex:Exception =>
          val pos = unpacker.getReadByteCount + offset
          throw new CodecException(f"message restoration failed; type=0x${msgType & 0xFF}%02X ('${msgType.toChar}%s'), length=$length%,d; $pos @ ${Debug.toString(binary)}", ex)
      }
    }
  }

  private[this] object SYNC_SESSION extends ObjectMapper[SyncSession] {
    override def encode(packer:Packer, sync:SyncSession):Unit = {
      packer
        .write(sync.version.version)
        .write(sync.utcTime)
      packer.writeMapBegin(sync.config.size)
      sync.config.foreach { case (key, value) =>
        packer.write(key).write(value)
      }
      packer.writeMapEnd(true)
    }

    override def decode(unpacker:Unpacker):SyncSession = {
      val version = Version(unpacker.readInt())
      val utcTime = unpacker.readLong()
      val size = unpacker.readMapBegin()
      val config = (0 until size).map { _ =>
        val key = unpacker.readString()
        val value = unpacker.readString()
        (key, value)
      }.toMap
      unpacker.readMapEnd(true)
      SyncSession(version, utcTime, config)
    }
  }

}
