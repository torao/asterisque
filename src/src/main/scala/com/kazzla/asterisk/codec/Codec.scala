/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.codec

import java.nio.ByteBuffer
import com.kazzla.asterisk.Message

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Codec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
object Codec {
	val MaxMessageSize = 0xFFFF
}

trait Codec {
	def encode(msg:Message):ByteBuffer
	def decode(buffer:ByteBuffer):Option[Message]
}

class CodecException(msg:String, ex:Throwable = null) extends Exception(msg, ex)
