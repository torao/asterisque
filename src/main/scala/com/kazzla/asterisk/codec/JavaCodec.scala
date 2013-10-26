/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.codec

import com.kazzla.asterisk.Message
import java.nio.ByteBuffer
import java.io._
import scala.Some

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// JavaCodec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * Message codec implementation using Java object serialization.
 * A Message will encode to 2 byte binary length and serialized binary.
 *
 * @author Takami Torao
 */
object JavaCodec extends Codec {

	/**
	 * Java シリアライゼーションを使用してメッセージをエンコードします。
	 * メッセージは 2 バイトの長さとシリアライズされたバイナリに変換されます。
	 * @param msg エンコードするメッセージ
	 * @return エンコードされたメッセージ
	 */
	def encode(msg:Message):ByteBuffer = {
		val baos = new ByteArrayOutputStream()
		baos.write(Array[Byte](0, 0))
		val out = new ObjectOutputStream(baos)
		out.writeObject(msg)
		out.flush()
		out.close()
		val buffer = ByteBuffer.wrap(baos.toByteArray)
		if(buffer.remaining() > Codec.MaxMessageSize){
			throw new CodecException(f"serialized size too long: ${buffer.remaining()}%,d bytes > ${Codec.MaxMessageSize}%,d bytes")
		}
		buffer.position(0)
		buffer.putShort((buffer.remaining() - 2).toShort)
		buffer.position(0)
		buffer
	}

	/**
	 * Java シリアライゼーションを使用してメッセージをデコードします。
	 * @param buffer デコードするメッセージ
	 * @return デコードしたメッセージ
	 */
	def decode(buffer:ByteBuffer):Option[Message] = try {
		if(buffer.remaining() < 2){
			None
		} else {
			val len = buffer.getShort & 0xFFFF
			if(buffer.remaining() < len){
				buffer.position(buffer.position() - 2)
				None
			} else {
				val buf = new Array[Byte](len)
				buffer.get(buf)
				val bais = new ByteArrayInputStream(buf)
				val in = new ObjectInputStream(bais)
				Some(in.readObject().asInstanceOf[Message])
			}
		}
	} catch {
		case ex:InvalidClassException =>
			throw new CodecException(s"${ex.classname}", ex)
		case ex:Exception =>
			throw new CodecException("invalid serialization stream", ex)
	}

}