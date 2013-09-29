/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.io.{EOFException, IOException}
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.JavaConversions._
import org.slf4j.LoggerFactory
import org.msgpack.{MessageTypeException, MessagePack}
import org.msgpack.packer.BufferPacker
import org.msgpack.unpacker.BufferUnpacker

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Message
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
sealed abstract class Message(val pipeId:Short)

case class Open(override val pipeId:Short, function:Short, params:AnyRef*) extends Message(pipeId)

case class Close[T](override val pipeId:Short, result:T, errorMessage:String) extends Message(pipeId)

/**
 * 長さが 0 のブロックは EOF を表します。
 */
case class Block(override val pipeId:Short, payload:Array[Byte], offset:Int, length:Int) extends Message(pipeId) {
	def isEOF:Boolean = length == 0
}

object Block {
	private[this] val empty = Array[Byte]()
	def eof(id:Short) = Block(id, empty)
	def apply(pipeId:Short, binary:Array[Byte]):Block = Block(pipeId, binary, 0, binary.length)
}

object Message {
	private[Message] val logger = LoggerFactory.getLogger(classOf[Message])
	val TYPE_OPEN:Byte = 1
	val TYPE_CLOSE:Byte = 2
	val TYPE_BLOCK:Byte = 3

	def encode(packet:Message):ByteBuffer = {
		val msgpack = new MessagePack()
		val packer = msgpack.createBufferPacker()
		packet match {
			case o:Open =>
				packer.write(TYPE_OPEN)
				packer.write(o.pipeId)
				packer.write(o.function)
				encode(packer, o.params.toSeq)
			case c:Close[_] =>
				packer.write(TYPE_CLOSE)
				packer.write(c.pipeId)
				packer.write(c.errorMessage == null)
				if(c.errorMessage == null){
					encode(packer, c.result)
				} else {
					encode(packer, c.errorMessage)
				}
			case b:Block =>
				packer.write(TYPE_BLOCK)
				packer.write(b.pipeId)
				packer.write(b.payload, b.offset, b.length)
		}
		if(logger.isTraceEnabled){
			logger.trace(s"encode($packet):${packer.getBufferSize} bytes")
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
				Some(Open(pipeId, port, params:_*))
			case TYPE_CLOSE =>
				val pipeId = unpacker.readShort()
				val success = unpacker.readBoolean()
				val (result, error) = if(success){
					(decode(unpacker), null)
				} else {
					(null, decode(unpacker).asInstanceOf[String])
				}
				buffer.position(unpacker.getReadByteCount)
				Some(Close(pipeId, result, error))
			case TYPE_BLOCK =>
				val pipeId = unpacker.readShort()
				val binary = unpacker.readByteArray()
				buffer.position(unpacker.getReadByteCount)
				Some(Block(pipeId, binary))
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

	private[this] def encode(packer:BufferPacker, value:Any):Unit = {
		if(value == null){
			packer.write(0.toByte)
			packer.writeNil()
		} else value match {
			case i:Boolean =>
				packer.write(1.toByte)
				packer.write(i)
			case i:Byte =>
				packer.write(2.toByte)
				packer.write(i)
			case i:Char =>
				packer.write(3.toByte)
				packer.write(i.toShort)
			case i:Short =>
				packer.write(4.toByte)
				packer.write(i)
			case i:Int =>
				packer.write(5.toByte)
				packer.write(i)
			case i:Long =>
				packer.write(6.toByte)
				packer.write(i)
			case i:Float =>
				packer.write(7.toByte)
				packer.write(i)
			case i:Double =>
				packer.write(8.toByte)
				packer.write(i)
			case i:Array[Byte] =>
				packer.write(9.toByte)
				packer.write(i)
			case i:String =>
				packer.write(10.toByte)
				packer.write(i)
			case i:UUID =>
				packer.write(11.toByte)
				packer.write(i.getMostSignificantBits)
				packer.write(i.getLeastSignificantBits)
			case i:Map[_,_] =>
				packer.write(101.toByte)
				packer.writeMapBegin(i.size * 4)
				i.foreach{ case (k, v) =>
					encode(packer, k)
					encode(packer, v)
				}
				packer.writeMapEnd()
			case i:Seq[_] =>
				packer.write(100.toByte)
				packer.writeArrayBegin(i.size * 2)    // type x value x n
				i.foreach{ x =>
					encode(packer, x)
				}
				packer.writeArrayEnd()
			case i:java.util.List[_] =>
				packer.write(100.toByte)
				packer.writeArrayBegin(i.size())
				i.foreach{ x => encode(packer, x) }
				packer.writeArrayEnd()
			case unsupported =>
				throw new CodecException(s"unsupported data-type: ${unsupported.getClass} ($unsupported)")
		}
	}

	private[this] def decode(unpacker:BufferUnpacker):Any = {
		unpacker.readByte() match {
			case 0 =>
				unpacker.readNil()
				null
			case 1 => unpacker.readBoolean()
			case 2 => unpacker.readByte()
			case 3 => unpacker.readShort().toChar
			case 4 => unpacker.readShort()
			case 5 => unpacker.readInt()
			case 6 => unpacker.readLong()
			case 7 => unpacker.readFloat()
			case 8 => unpacker.readDouble()
			case 9 => unpacker.readByteArray()
			case 10 => unpacker.readString()
			case 11 => new UUID(unpacker.readLong(), unpacker.readLong())
			case 100 =>
				val length = unpacker.readArrayBegin() / 2
				val array = for(i <- 0 until length) yield{
					decode(unpacker)
				}
				unpacker.readArrayEnd()
				array.toArray
			case 101 =>
				val length = unpacker.readMapBegin() / 4
				val map = (0 until length).map{ _ =>
					val k = decode(unpacker)
					val v = decode(unpacker)
					k -> v
				}.toMap
				unpacker.readMapEnd()
				map
			case unsupported =>
				throw new CodecException(f"unsupported data-type: 0x$unsupported%02X")
		}
	}

	case class CodecException(msg:String) extends IOException(msg)

	trait ReadableQueue {
		def head():Option[Message]
		def take():Option[Message]
		def onPut(f: =>Unit):Unit
		def onEmpty(f: =>Unit):Unit
	}

	trait WritableQueue {
		def put(frame:Message):Unit
	}

	final class Queue extends WritableQueue with ReadableQueue {
		private[this] val queue = new AtomicReference(List[Message]())
		private[this] var _onPut:Option[()=>Unit] = None
		private[this] var _onEmpty:Option[()=>Unit] = None

		@tailrec
		def put(frame:Message):Unit = {
			val q = queue.get()
			if(queue.compareAndSet(q, q :+ frame)){
				_onPut.foreach { _() }
			} else {
				put(frame)
			}
		}

		def head():Option[Message] = {
			val q = queue.get()
			if(q.size == 0){
				None
			} else {
				Some(q.head)
			}
		}

		@tailrec
		def take():Option[Message] = {
			val q = queue.get()
			if(q.size == 0){
				return None
			} else if(queue.compareAndSet(q, q.drop(1))){
				if(q.size == 1){
					_onEmpty.foreach{ _() }
				}
				return Some(q.head)
			}
			take()
		}

		def onPut(f: =>Unit):Unit = {
			_onPut = Some({ () => f })
		}
		def onEmpty(f: =>Unit):Unit = {
			_onEmpty = Some({ () => f })
		}

	}
}
