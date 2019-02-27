package io.asterisque.wire.message

import java.util.Objects

import javax.annotation.Nonnull


/**
  * 処理の中断状況を表すために `Close` に付加される情報です。
  * 中断コードとメッセージを指定して構築を行います。
  *
  * @param code    識別コード
  * @param message 人間が読める形式の中断理由
  * @author Takami Torao
  */
final case class Abort(code:Int, @Nonnull message:String) extends Exception(s"$code: $message") {
  Objects.requireNonNull(message, "message shouldn't be null")
}

object Abort {

  /**
    * 予期しない状況によって処理が中断したことを示すコード。
    */
  val Unexpected:Int = -1

  /**
    * セッションがクローズ処理に入ったため処理が中断されたことを示すコード。
    */
  val SessionClosing:Int = -2

  /**
    * サービスが見つからない事を示すコード。
    */
  val ServiceUndefined = 100

  /**
    * サービスに function id が定義されていないことを示すコード。
    */
  val FunctionUndefined = 101

  /**
    * function の実行に失敗したことを示すコード。
    */
  val FunctionAborted = 102

  /**
    * ブロックを受信できない function にブロックが送信されたことを示すコード。
    */
  val FunctionCannotReceiveBlock = 103
}