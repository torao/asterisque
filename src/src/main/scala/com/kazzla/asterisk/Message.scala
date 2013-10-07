/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

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
case class Open(override val pipeId:Short, function:Short, params:Any*) extends Message(pipeId) {
	override def toString = s"${getClass.getSimpleName}($pipeId,$function,${debugString(params)})"
}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Close
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
case class Close[T](override val pipeId:Short, result:T, errorMessage:String) extends Message(pipeId) {
	override def toString = s"${getClass.getSimpleName}($pipeId,${debugString(result)},${debugString(errorMessage)})"
}

/**
 * 長さが 0 のブロックは EOF を表します。
 */
case class Block(override val pipeId:Short, payload:Array[Byte], offset:Int, length:Int) extends Message(pipeId) {
	def isEOF:Boolean = length == 0
	override def toString = "%s(%d,[%s],%d,%d)".format(getClass.getSimpleName, pipeId, payload.map {
		b => "%02X".format(b & 0xFF)
	}.mkString(","), offset, length)
}

object Block {
	private[this] val empty = Array[Byte]()
	def eof(id:Short) = Block(id, empty)
	def apply(pipeId:Short, binary:Array[Byte]):Block = Block(pipeId, binary, 0, binary.length)
}
