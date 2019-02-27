package io.asterisque.wire.message

import java.security.cert.X509Certificate
import java.util.ServiceLoader

import io.asterisque.auth.{Algorithms, Certificate}
import io.asterisque.core.session.ProtocolViolationException
import io.asterisque.utils.{Debug, Version}
import io.asterisque.wire.{Envelope, RemoteException}
import javax.annotation.{Nonnull, Nullable}
import org.msgpack.MessagePack
import org.msgpack.packer.Packer
import org.msgpack.unpacker.Unpacker
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.{Failure, Success}

/**
  * 特定の型に対して直列化と復元を実装します。
  *
  * @tparam T 変換するオブジェクトの型
  */
trait Codec[T] {

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
  def decode(@Nonnull binary:Array[Byte]):T = {
    val unpacker = new MessagePack().createBufferUnpacker(binary)
    decode(unpacker)
  }
}

object Codec {
  private[this] val logger = LoggerFactory.getLogger(classOf[Codec[_]])

  private[this] val codecs = ServiceLoader.load(classOf[Codec[Any]]).iterator().asScala.toSeq
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
  }.getOrElse {
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

  implicit object MESSAGE extends Codec[Message] {
    override def encode(packer:Packer, msg:Message):Unit = msg match {
      case open:Open =>
        packer.write(Msg.Open)
        packer.write(open.pipeId)
        packer.write(open.priority)
        packer.write(open.functionId)
        writeArray(packer, open.params)
      case close:Close[_] =>
        packer.write(Msg.Close)
        packer.write(close.pipeId)
        close.result match {
          case Success(x) =>
            packer.write(true)
            Codec.encode(packer, x)
          case Failure(ex) =>
            packer.write(false)
            ex match {
              case Abort(code, message) =>
                packer.write(code)
                packer.write(message)
              case _ =>
                packer.write(Abort.Unexpected)
                packer.write(ex.toString)
            }
        }
      case block:Block =>
        assert(block.loss >= 0)
        assert(block.length <= Block.MaxPayloadSize, f"block payload length too large: ${block.length}%d / ${Block.MaxPayloadSize}%d")
        packer.write(Msg.Block)
        val status = ((if(block.eof) 1 << 7 else 0) | (block.loss & 0x7F)).toByte
        packer.write(block.pipeId)
        packer.write(status)
        packer.write(block.payload, block.offset, block.length)
      case control:Control =>
        packer.write(Msg.Control)
        packer.write(control.code)
        packer.write(control.data)
    }

    override def decode(unpacker:Unpacker):Message = unpacker.readByte() match {
      case Msg.Open =>
        val pipeId = unpacker.readShort()
        val priority = unpacker.readByte()
        val functionId = unpacker.readShort()
        val params = readArray(unpacker).toArray
        unpacker.readArrayEnd(true)
        Open(pipeId, priority, functionId, params)
      case Msg.Close =>
        val pipeId = unpacker.readShort()
        val success = unpacker.readBoolean()
        if(success) {
          val result = Codec.decode(unpacker)
          Close(pipeId, Success(result))
        } else {
          val msg = unpacker.readString()
          Close(pipeId, Failure(new RemoteException(msg)))
        }
      case Msg.Block =>
        val pipeId = unpacker.readShort()
        val status = unpacker.readByte()
        val eof = ((1 << 7) & status) != 0
        val loss = (status & 0x7F).toByte
        val payload = unpacker.readByteArray()
        Block(pipeId, loss, payload, 0, payload.length, eof)
      case Msg.Control =>
        val code = unpacker.readByte()
        val data = unpacker.readByteArray()
        Control(code, data)
    }
  }

  implicit object SYNC_SESSION extends Codec[SyncSession] {
    override def encode(packer:Packer, sync:SyncSession):Unit = {
      packer.write(sync.version.toInt)
      CERTIFICATE.encode(packer, sync.cert)
      packer.write(sync.serviceId).write(sync.utcTime)
      MAP_STRING.encode(packer, sync.config)
    }

    override def decode(unpacker:Unpacker):SyncSession = {
      val version = Version(unpacker.readInt())
      val cert = CERTIFICATE.decode(unpacker)
      val sessionId = unpacker.readLong()
      val serviceId = unpacker.readString()
      val utcTime = unpacker.readLong()
      val attr = MAP_STRING.decode(unpacker)
      SyncSession(version, cert, sessionId, serviceId, utcTime, attr)
    }
  }

  implicit object CERTIFICATE extends Codec[Certificate] {
    override def encode(packer:Packer, cert:Certificate):Unit = {
      X509CERTIFICATE.encode(packer, cert.cert)
      MAP_STRING.encode(packer, cert.attrs)
    }

    override def decode(unpacker:Unpacker):Certificate = {
      Certificate(X509CERTIFICATE.decode(unpacker), MAP_STRING.decode(unpacker))
    }
  }

  implicit object X509CERTIFICATE extends Codec[X509Certificate] {

    import io.asterisque.carillon._

    override def encode(packer:Packer, cert:X509Certificate):Unit = {
      packer.write(cert.toByteArray)
    }

    override def decode(unpacker:Unpacker):X509Certificate = {
      Algorithms.Certificate.load(unpacker.readByteArray())
    }
  }

  implicit object ENVELOPE extends Codec[Envelope] {
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

  implicit object MAP_STRING extends Codec[Map[String, String]] {
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
  private[message] def writeMap(packer:Packer, map:scala.collection.Map[_, _]):Unit = {
    assert(map.size <= 0xFFFF)
    packer.write(map.size.toShort)
    map.foreach { case (key, value) =>
      Codec.encode(packer, key)
      Codec.encode(packer, value)
    }
  }

  /**
    * [[writeMap()]] でシリアライズしたマップを復元する。
    *
    * @param unpacker unpacker
    * @return 復元したマップ
    */
  private[message] def readMap(unpacker:Unpacker):Map[_, _] = {
    val size = unpacker.readShort() & 0xFFFF
    (0 until size).map { _ =>
      val key = Codec.decode(unpacker)
      val value = Codec.decode(unpacker)
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
  private[message] def writeArray(packer:Packer, array:Iterable[_]):Unit = {
    assert(array.size <= 0xFFFF)
    packer.write(array.size.toShort)
    array.foreach(value => Codec.encode(packer, value))
  }

  /**
    * [[writeArray()]] でシリアライズしたアレイを復元する。
    *
    * @param unpacker unpacker
    * @return 復元したアレイ
    */
  private[message] def readArray(unpacker:Unpacker):Iterable[Any] = {
    val size = unpacker.readShort() & 0xFFFF
    (0 until size).map(_ => Codec.decode(unpacker))
  }

}
