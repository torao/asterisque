/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.nio.ByteBuffer

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Message
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
sealed abstract class Message(val pipeId:Short) extends Serializable

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Open
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
case class Open(override val pipeId:Short, function:Short, params:Seq[Any]) extends Message(pipeId) {
	override def toString = s"${getClass.getSimpleName}($pipeId,$function,${debugString(params)})"
}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Close
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
case class Close(override val pipeId:Short, result:Either[String,Any]) extends Message(pipeId) {
	//override def toString = s"${getClass.getSimpleName}($pipeId,${result.left.map(debugString)},${result.right.map(debugString)})"
}

/**
 * 長さが 0 のブロックは EOF を表します。
 */
case class Block(override val pipeId:Short, payload:Array[Byte], offset:Int, length:Int) extends Message(pipeId) {

	// ==============================================================================================
	// EOF 判定
	// ==============================================================================================
	/**
	 * このブロックが EOF を表すかを判定します。
	 * @return EOF の場合 true
	 */
	def isEOF:Boolean = length == 0

	override def toString:String = {
		"%s(%d,[%s],%d,%d)".format(getClass.getSimpleName, pipeId, payload.map {
			b => "%02X".format(b & 0xFF)
		}.mkString(","), offset, length)
	}

	def toByteBuffer:ByteBuffer = ByteBuffer.wrap(payload, offset, length)
	def getString:String = new String(payload, offset, length)
	def getString(charset:String):String = new String(payload, offset, length, charset)
}

object Block {

	val MaxPayloadSize = 0xFFFF - (4 * 1024)

	/**
	 * EOF ブロックで共用する長さ 0 のバイト配列。
	 */
	private[this] val empty = Array[Byte]()

	// ==============================================================================================
	// EOF ブロックの作成
	// ==============================================================================================
	/**
	 * 指定されたパイプ ID を持つ EOF ブロックを作成します。
	 * @param id パイプ ID
	 * @return EOFブロック
	 */
	def eof(id:Short) = Block(id, empty)

	def apply(pipeId:Short, binary:Array[Byte]):Block = Block(pipeId, binary, 0, binary.length)
}
