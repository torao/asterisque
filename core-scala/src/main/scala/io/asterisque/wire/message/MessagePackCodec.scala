package io.asterisque.wire.message

import java.io.{InputStream, OutputStream}
import java.lang.reflect.Method
import java.util.UUID

import io.asterisque.wire.message.MessagePackCodec.Tag
import javax.annotation.{Nonnull, Nullable}
import org.msgpack.MessagePack
import org.msgpack.packer.Packer
import org.msgpack.unpacker.Unpacker

import scala.collection.JavaConverters._

class MessagePackCodec extends Codec {
  private[this] val msgpack = new MessagePack()

  def encode(@Nonnull out:OutputStream, @Nonnull method:Method, isParams:Boolean, @Nullable value:Object):Unit = {
    val packer = msgpack.createPacker(out)
    encode(packer, value)
    packer.flush()
  }

  def decode(@Nonnull in:InputStream, @Nonnull method:Method, isParams:Boolean):Object = {
    val unpacker = msgpack.createUnpacker(in)
    decode(unpacker)
  }

  private[this] def encodeInt(packer:Packer, value:Long):Unit = if(value >= Byte.MinValue && value <= Byte.MaxValue) {
    packer.write(Tag.Int8).write(value.toByte)
  } else if(value >= Short.MinValue && value <= Short.MaxValue) {
    packer.write(Tag.Int16).write(value.toShort)
  } else if(value >= Int.MinValue && value <= Int.MaxValue) {
    packer.write(Tag.Int32).write(value.toInt)
  } else {
    packer.write(Tag.Int64).write(value)
  }

  private[this] def encodeString(packer:Packer, value:String):Unit = {
    packer.write(Tag.String).write(value)
  }

  private[this] def encodeBinary(packer:Packer, value:Array[Byte]):Unit = {
    packer.write(Tag.Binary).write(value)
  }

  /**
    * asterisque がサポートする任意のデータを [[MessagePackCodec.Tag]] 付きで書き込みます。
    *
    * @param _value 転送可能型の値
    * @throws CodecException 指定された値のエンコードに対応していない場合
    */
  @throws[CodecException]
  private[this] def encode(packer:Packer, _value:Any):Unit = _value match {
    case null | () => packer.write(Tag.Null)
    case b:Boolean => packer.write(if(b) Tag.True else Tag.False)
    case i:Byte => encodeInt(packer, i.toLong)
    case i:Short => encodeInt(packer, i.toLong)
    case i:Int => encodeInt(packer, i.toLong)
    case i:Long => encodeInt(packer, i)
    case f:Float =>
      packer.write(Tag.Float32)
      packer.write(f)
    case f:Double =>
      packer.write(Tag.Float64)
      packer.write(f)
    case c:Char => encodeString(packer, c.toString)
    case b:Array[Byte] => encodeBinary(packer, b)
    case s:String => encodeString(packer, s)
    case u:UUID =>
      packer.write(Tag.UUID)
      packer.write(u.getMostSignificantBits)
      packer.write(u.getLeastSignificantBits)
    case i:BigInt => encodeBinary(packer, i.toByteArray)
    case i:BigDecimal => encodeString(packer, i.toString)
    case d:java.util.Date =>
      encodeInt(packer, d.getTime)
    case m:Map[_, _] =>
      packer.write(Tag.Map)
      ObjectMapper.writeMap(packer, m)
    case l:Iterable[_] =>
      packer.write(Tag.List)
      ObjectMapper.writeArray(packer, l)
    case l:Array[_] =>
      packer.write(Tag.List)
      ObjectMapper.writeArray(packer, l)
    case t:Product =>
      packer.write(Tag.List)
      ObjectMapper.writeArray(packer, (0 until t.productArity).map(i => t.productElement(i)))

    // Java プリミティブ型と標準コレクション型
    case b:java.lang.Boolean => packer.write(if(b.booleanValue()) Tag.True else Tag.False)
    case i:java.lang.Byte => encodeInt(packer, i.longValue())
    case i:java.lang.Short => encodeInt(packer, i.longValue())
    case i:java.lang.Integer => encodeInt(packer, i.longValue())
    case i:java.lang.Long => encodeInt(packer, i.longValue())
    case f:java.lang.Float =>
      packer.write(Tag.Float32)
      packer.write(f.floatValue())
    case f:java.lang.Double =>
      packer.write(Tag.Float64)
      packer.write(f.doubleValue())
    case c:java.lang.Character => encodeString(packer, c.toString)
    case s:java.lang.String => encodeString(packer, s)
    case i:java.math.BigInteger => encodeBinary(packer, i.toByteArray)
    case i:java.math.BigDecimal => encodeString(packer, i.toString)
    case dt:java.util.Date => encodeInt(packer, dt.getTime)
    case m:java.util.Map[_, _] =>
      packer.write(Tag.List)
      ObjectMapper.writeMap(packer, m.asScala)
    case l:java.util.Collection[_] =>
      packer.write(Tag.List)
      ObjectMapper.writeArray(packer, l.asScala)
    case unsupported =>
      throw new CodecException(s"marshal not supported for data type: ${unsupported.getClass.getCanonicalName}: $unsupported")
  }

  /**
    * 指定された `unpacker` からオブジェクトを復元します。
    *
    * @param unpacker Unpacker
    * @return 復元したオブジェクト
    * @throws CodecException オブジェクトの復元に失敗した場合
    */
  @throws[CodecException]
  private[this] def decode(unpacker:Unpacker):Object = unpacker.readByte() match {
    case Tag.Null => null
    case Tag.True => java.lang.Boolean.TRUE
    case Tag.False => java.lang.Boolean.FALSE
    case Tag.Int8 => java.lang.Byte.valueOf(unpacker.readByte())
    case Tag.Int16 => java.lang.Short.valueOf(unpacker.readShort())
    case Tag.Int32 => java.lang.Integer.valueOf(unpacker.readInt())
    case Tag.Int64 => java.lang.Long.valueOf(unpacker.readLong())
    case Tag.Float32 => java.lang.Float.valueOf(unpacker.readFloat())
    case Tag.Float64 => java.lang.Double.valueOf(unpacker.readDouble())
    case Tag.String => unpacker.readString()
    case Tag.UUID =>
      val mostSignificantBits = unpacker.readLong()
      val leastSignificantBits = unpacker.readLong()
      new UUID(mostSignificantBits, leastSignificantBits)
    case Tag.Map =>
      val size = unpacker.readMapBegin()
      val map = (0 until size).map { _ =>
        (ObjectMapper.decode(unpacker), ObjectMapper.decode(unpacker))
      }.toMap
      unpacker.readMapEnd(true)
      map
    case Tag.List =>
      val size = unpacker.readArrayBegin()
      val list = (0 until size).map(_ => ObjectMapper.decode(unpacker))
      unpacker.readArrayEnd(true)
      list
    case unsupported =>
      throw new CodecException(s"marshal not supported for data type: ${unsupported.getClass.getCanonicalName}: $unsupported")
  }
}

object MessagePackCodec {

  object Tag {
    val Null:Byte = 0
    val True:Byte = 1
    val False:Byte = 2
    val Int8:Byte = 3
    val Int16:Byte = 4
    val Int32:Byte = 5
    val Int64:Byte = 6
    val Float32:Byte = 7
    val Float64:Byte = 8
    val Binary:Byte = 10
    val String:Byte = 11
    val UUID:Byte = 12
    val List:Byte = 32
    val Map:Byte = 33
  }

}