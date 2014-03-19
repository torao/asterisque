/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.codec

import org.msgpack.packer.BufferPacker
import java.util.UUID
import scala.collection.JavaConversions._
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import org.msgpack.{MessageTypeException, MessagePack}
import java.io.EOFException
import org.msgpack.unpacker.BufferUnpacker
import com.kazzla.asterisk._
import com.kazzla.asterisk.Close
import com.kazzla.asterisk.Open
import scala.Some

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// MsgPackCodec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * MessagePack を使用したコーデックです。
 *
 * @author Takami Torao
 */
object MsgPackCodec extends Codec {
	private[this] val logger = LoggerFactory.getLogger(classOf[Message])

	/**
	 * [[com.kazzla.asterisk.Open]] メッセージを表すメッセージタイプ。
	 */
	val TYPE_OPEN:Byte = 1

	/**
	 * [[com.kazzla.asterisk.Close]] メッセージを表すメッセージタイプ。
	 */
	val TYPE_CLOSE:Byte = 2

	/**
	 * [[com.kazzla.asterisk.Block]] メッセージを表すメッセージタイプ。
	 */
	val TYPE_BLOCK:Byte = 3

	/**
	 * メッセージタイプ + 各パラメータの形式でエンコードします。
	 * @param packet エンコードするメッセージ
	 * @return エンコードされたメッセージ
	 */
	def encode(packet:Message):ByteBuffer = {
		val msgpack = new MessagePack()
		val packer = msgpack.createBufferPacker()
		packet match {
			case o:Open =>
				packer.write(TYPE_OPEN)
				packer.write(o.pipeId)
				packer.write(o.function)
				encode(packer, o.params.toSeq)
			case c:Close =>
				packer.write(TYPE_CLOSE)
				packer.write(c.pipeId)
				packer.write(c.result.isRight)
				if(c.result.isRight){
					encode(packer, c.result.right.get)
				} else {
					val abort = c.result.left.get
					packer.write(abort.code)
					packer.write(abort.message)
					packer.write(abort.description)
				}
			case b:Block =>
				packer.write(TYPE_BLOCK)
				packer.write(b.pipeId)
				packer.write(b.eof)
				if(! b.eof){
					packer.write(b.payload, b.offset, b.length)
				}
		}
		if(logger.isTraceEnabled){
			logger.trace(s"encode:$packet: ${packer.getBufferSize}bytes")
		}
		ByteBuffer.wrap(packer.toByteArray)
	}

	def decode(buffer:ByteBuffer):Option[Message] = try {
		val msgpack = new MessagePack()
		val unpacker = msgpack.createBufferUnpacker(buffer)
		unpacker.readByte() match {
			case TYPE_OPEN =>
				val pipeId = unpacker.readShort()
				val port = unpacker.readShort()
				val params = decode(unpacker).asInstanceOf[Array[AnyRef]]
				buffer.position(unpacker.getReadByteCount)
				Some(Open(pipeId, port, params))
			case TYPE_CLOSE =>
				val pipeId = unpacker.readShort()
				val success = unpacker.readBoolean()
				val result = if(success){
					Right(decode(unpacker))
				} else {
					val code = unpacker.readInt()
					val msg = unpacker.readString()
					val desc = unpacker.readString()
					Left(Abort(code, msg, desc))
				}
				buffer.position(unpacker.getReadByteCount)
				Some(Close(pipeId, result))
			case TYPE_BLOCK =>
				val pipeId = unpacker.readShort()
				val eof = unpacker.readBoolean()
				val binary = if(! eof){
					unpacker.readByteArray()
				} else {
					Array[Byte](0)
				}
				buffer.position(unpacker.getReadByteCount)
				Some(if(eof) Block.eof(pipeId) else Block(pipeId, binary))
			case unknown =>
				throw new CodecException(f"unsupported frame-type: 0x$unknown%02X")
		}
	} catch {
		case ex:EOFException =>
			// logger.trace(ex.toString)
			None
		case ex:MessageTypeException =>
			logger.trace("", ex)
			None
	}

	def encode(packer:BufferPacker, value:Any):Unit = {
		value match {
			case null =>
				packer.write(0.toByte)
			case () =>
				packer.write(1.toByte)
			case i:Boolean =>
				packer.write(2.toByte)
				packer.write(i)
			case i:Byte =>
				packer.write(3.toByte)
				packer.write(i)
			case i:Char =>
				packer.write(4.toByte)
				packer.write(i.toShort)
			case i:Short =>
				packer.write(5.toByte)
				packer.write(i)
			case i:Int =>
				packer.write(6.toByte)
				packer.write(i)
			case i:Long =>
				packer.write(7.toByte)
				packer.write(i)
			case i:Float =>
				packer.write(8.toByte)
				packer.write(i)
			case i:Double =>
				packer.write(9.toByte)
				packer.write(i)
			case i:Array[Byte] =>
				packer.write(10.toByte)
				packer.write(i)
			case i:String =>
				packer.write(11.toByte)
				packer.write(i)
			case i:UUID =>
				packer.write(12.toByte)
				packer.write(i.getMostSignificantBits)
				packer.write(i.getLeastSignificantBits)
			case i:Map[_,_] =>
				packer.write(101.toByte)
				packer.write(i.size)
				// packer.writeMapBegin(i.size * 4)   // writeMapBegin/End を使用すると型情報が付いているため要素数と合わない
				i.foreach{ case (k, v) =>
					encode(packer, k)
					encode(packer, v)
				}
				// packer.writeMapEnd()
			case i:java.util.Map[_,_] => encode(packer, i.toMap)
			case i:Seq[_] =>
				packer.write(100.toByte)
				packer.write(i.size)
				// packer.writeArrayBegin(i.size * 2)    // type x value x n
				i.foreach{ x =>
					encode(packer, x)
				}
				// packer.writeArrayEnd()
			case i:Array[_] => encode(packer, i.toSeq)
			case i:java.util.List[_] => encode(packer, i.toSeq)
			case i:Product =>
				if(i.productArity > Codec.MaxCaseClassElements){
					throw new CodecException(
						s"too much property: ${i.getClass.getSimpleName}, ${i.productArity} > ${Codec.MaxCaseClassElements}")
				}
				packer.write(102.toByte)
				packer.write(i.getClass.getName)
				packer.write(i.productArity.toByte)   // unsigned char
				(0 until i.productArity).foreach { j =>
					encode(packer, i.productElement(j))
				}
			case unsupported =>
				throw new CodecException(s"unsupported data-type: ${unsupported.getClass} ($unsupported)")
		}
	}

	def decode(unpacker:BufferUnpacker):Any = {
		unpacker.readByte() match {
			case 0 => null
			case 1 => ()
			case 2 => unpacker.readBoolean()
			case 3 => unpacker.readByte()
			case 4 => unpacker.readShort().toChar
			case 5 => unpacker.readShort()
			case 6 => unpacker.readInt()
			case 7 => unpacker.readLong()
			case 8 => unpacker.readFloat()
			case 9 => unpacker.readDouble()
			case 10 => unpacker.readByteArray()
			case 11 => unpacker.readString()
			case 12 => new UUID(unpacker.readLong(), unpacker.readLong())
			case 100 =>
				val length = unpacker.readInt()
				// val length = unpacker.readArrayBegin() / 2
				val array = for(i <- 0 until length) yield{
					decode(unpacker)
				}
				// unpacker.readArrayEnd()
				array.toArray
			case 101 =>
				val length = unpacker.readInt()
				// val length = unpacker.readMapBegin() / 4
				val map = (0 until length).map{ _ =>
					val k = decode(unpacker)
					val v = decode(unpacker)
					k -> v
				}.toMap
				// unpacker.readMapEnd()
				map
			case 102 =>
				val className = unpacker.readString()
				val paramSize = unpacker.readByte() & Codec.MaxCaseClassElements
				val params = (0 until paramSize).map{ _ => decode(unpacker) }.toArray
				val objs = Class.forName(className).getConstructors.map{ c =>
					if(c.getParameterTypes.length == paramSize){
						try {
							val actualParams = TypeMapper.appropriateValues(params, c.getParameterTypes)
							Some(c.newInstance(actualParams:_*))
						} catch {
							case ex:TypeMapper.TypeMappingException =>
								logger.trace(s"incompatible: ${c.getSimpleName}; ${ex.toString}")
								None
						}
					} else {
						logger.trace(s"incompatible: ${c.getSimpleName}")
						None
					}
				}.filter{ _.isDefined }.map{ _.get }
				if(objs.length == 0){
					throw new CodecException(
						s"appropriate constructor not found on class $className: ${debugString(params)}")
				} else if(objs.length > 1){
					throw new CodecException(
						s"${objs.length} compatible constructors are found on class $className: ${debugString(params)}")
				} else {
					objs(0)
				}
			case unsupported =>
				throw new CodecException(f"unsupported data-type: 0x$unsupported%02X")
		}
	}

}
