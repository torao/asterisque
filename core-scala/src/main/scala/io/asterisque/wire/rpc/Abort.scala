package io.asterisque.wire.rpc

import java.util.Objects

import io.asterisque.wire.message.Message.Close
import javax.annotation.Nonnull

/**
  * 処理の中断状況を表すために Pipe の Future に設定される例外です。
  * 中断コードとメッセージを指定して構築を行います。
  *
  * @param code    識別コード
  * @param message 人間が読める形式の中断理由
  * @author Takami Torao
  */
final class Abort private[rpc](val code:Byte, @Nonnull val message:String) extends Exception(f"$code: $message") {
  Objects.requireNonNull(message, "message shouldn't be null")
  if(code == Close.Code.SUCCESS) {
    throw new IllegalArgumentException(s"code $code cannot use for abort: $message")
  }
}

object Abort {

  /**
    * 予期しない状況によって処理が中断したことを示すコード。
    */
  val Unexpected:Byte = -1

  /**
    * セッションがクローズ処理に入ったため処理が中断されたことを示すコード。
    */
  val SessionClosing:Byte = -2

  /**
    * サービスが見つからない事を示すコード。
    */
  val ServiceUndefined:Byte = 100

  /**
    * サービスに function id が定義されていないことを示すコード。
    */
  val FunctionUndefined:Byte = 101

  /**
    * function の実行に失敗したことを示すコード。
    */
  val FunctionAborted:Byte = 102

  /**
    * ブロックを受信できない function にブロックが送信されたことを示すコード。
    */
  val FunctionCannotReceiveBlock:Byte = 103

  /**
    * 宛先のパイプが存在しない事を示すコード。
    */
  val DestinationPipeUnreachable:Byte = 104
}