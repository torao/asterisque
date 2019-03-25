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
case class Close(override val pipeId:Short, result:Either[Abort,Any]) extends Message(pipeId) {
  //override def toString = s"${getClass.getSimpleName}($pipeId,${result.left.map(debugString)},${result.right.map(debugString)})"
}

object Close {
  val UnexpectedErrorCode = -1
  def error(pipeId:Short, error:Abort):Close = Close(pipeId, Left(error))
  def error(pipeId:Short, code:Int, message:String, description:String):Close = error(pipeId, Abort(code, message, description))
  def unexpectedError(pipeId:Short, ex:Throwable) = ex match {
    case Abort(code, msg, desc) => error(pipeId, code, msg, desc)
    case _ =>
      val message = Option(ex.getMessage) match {
        case Some("") => ex.getClass.getName
        case Some(msg) => msg
        case None => ex.getClass.getName
      }
      error(pipeId, UnexpectedErrorCode, message, "")
  }
  def unexpectedError(pipeId:Short, msg:String) = error(pipeId, UnexpectedErrorCode, msg, "")
  def success(pipeId:Short, result:Any) = Close(pipeId, Right(result))
}

/**
 * [[Close]] のエラー情報。
 * @param code アプリケーションによって定義されるエラーコード
 * @param message エラーメッセージ
 * @param description エラーの詳細情報
 */
case class Abort(code:Int, message:String, description:String) extends Exception(s"$code: $message"){
  def toClose(pipeId:Short) = Close.unexpectedError(pipeId, this)
}
object Abort {
  def apply(code:Int, msg:String):Abort = Abort(code, msg, "")
}

case class Block(override val pipeId:Short, payload:Array[Byte], offset:Int, length:Int, eof:Boolean = false) extends Message(pipeId) {

  // ==============================================================================================
  // EOF 判定
  // ==============================================================================================
  /**
   * このブロックが EOF を表すかを判定します。
   * @return EOF の場合 true
   * @deprecated use `eof` parameter directly.
   */
  def isEOF:Boolean = eof

  override def toString:String = if(eof){
    s"$productPrefix(EOF)"
  } else {
    s"$productPrefix($pipeId,[${payload.map {
      b => "%02X".format(b & 0xFF)
    }.mkString(",")}],$offset,$length)"
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
  private[this] val EmptyArray = Array[Byte]()

  // ==============================================================================================
  // EOF ブロックの作成
  // ==============================================================================================
  /**
   * 指定されたパイプ ID を持つ EOF ブロックを作成します。
   * @param id パイプ ID
   * @return EOFブロック
   */
  def eof(id:Short) = Block(id, EmptyArray, 0, 0, eof = true)

  def apply(pipeId:Short, binary:Array[Byte]):Block = Block(pipeId, binary, 0, binary.length)
}
