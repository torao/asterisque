package io.asterisque.wire.message

import java.io.Serializable
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.Objects

import io.asterisque.core.Pipe
import io.asterisque.core.codec.VariableCodec
import io.asterisque.utils.Debug
import io.asterisque.{Asterisque, Priority}
import javax.annotation.{Nonnull, Nullable}
import org.apache.commons.codec.binary.Hex

import scala.util.{Failure, Success, Try}

/**
  * asterisque のワイヤープロトコルで使用するメッセージです。
  *
  * @author Takami Torao
  */
sealed trait Message extends Serializable {

  /**
    * メッセージの宛先となるパイプ ID
    */
  val pipeId:Short
}

object Message {

  /**
    * 特定のファンクションに対してパイプのオープンを要求するメッセージです。
    * オープン要求の対象となるサービスはセッション開始時に決定しています。
    *
    * @param pipeId     新しく要求するパイプの ID
    * @param priority   ファンクション呼び出しのプライオリティ
    * @param functionId オープンを要求するファンクション ID
    * @param params     ファンクションの呼び出しパラメータ
    * @author Takami Torao
    */
  final case class Open(pipeId:Short, priority:Byte, functionId:Short, @Nonnull params:Array[Byte]) extends Message {
    Objects.requireNonNull(params, "params shouldn't be null")
    assert(VariableCodec.isTransferable(java.util.Arrays.asList(params)), Debug.toString(params))
  }

  object Open {

    /**
      * デフォルトの優先度を持つ Open メッセージを構築します。
      *
      * @param pipeId     新しく要求するパイプの ID
      * @param functionId オープンを要求するファンクション ID
      * @param params     ファンクションの呼び出しパラメータ
      */
    def apply(pipeId:Short, functionId:Short, @Nonnull params:Array[Byte]):Open = Open(pipeId, Priority.Normal, functionId, params)
  }

  /**
    * パイプのクローズを示すメッセージです。`result` は処理が正常に終了した場合の結果を保持します。中断によって処理が
    * 終了した場合 `Abort` が設定されます。
    *
    * @param pipeId パイプ ID
    * @param result 処理結果
    */
  final case class Close(pipeId:Short, @Nonnull result:Try[Any]) extends Message

  object Close {

    /**
      * 処理成功の Close メッセージを構築します。
      *
      * @param pipeId 宛先のパイプ ID
      * @param result 成功として渡される結果
      */
    def withSuccessful[T](pipeId:Short, @Nullable result:T):Close = {
      assert(VariableCodec.isTransferable(result), Debug.toString(result))
      Close(pipeId, Success(result))
    }

    /**
      * 予期しない状態によってパイプを終了するときのクローズメッセージを作成します。
      *
      * @param pipeId 宛先のパイプ ID
      * @param abort  中断メッセージ
      * @return 予期しない状況による処理中断を表すクローズメッセージ
      */
    def withFailure(pipeId:Short, @Nonnull abort:Abort):Close = {
      Objects.requireNonNull(abort, "abort shouldn't be null")
      Close(pipeId, Failure(abort))
    }

    /**
      * 指定されたエラーコードによってパイプを終了するときのクローズメッセージを作成します。
      *
      * @param pipeId 宛先のパイプ ID
      * @param code   エラーコード
      * @param msg    エラーメッセージ
      * @return エラーによる処理中断を表すクローズメッセージ
      */
    @Nonnull
    def withFailure(pipeId:Short, code:Int, @Nonnull msg:String):Close = Close(pipeId, Failure(Abort(code, msg)))
  }

  /**
    * [[Pipe]] を経由して双方向で交換可能なメッセージです。パイプ間での双方向ストリーミングのために使用されます。
    * Block メッセージを構築します。`MaxPayloadSize` より大きいペイロードを指定すると例外が発生します。
    *
    * * 損失率はこのブロックが過負荷などによって消失しても良い確率を表す 0〜127 までの値です。0 はこのブロックが消失しないことを
    * * 表し、127 は 100% の消失が発生しても良い事を示します。EOF を示す場合でも 0 以外の値をとることことができます。
    *
    * @param pipeId  宛先のパイプ ID
    * @param loss    このブロックの損失率
    * @param payload このブロックが転送するデータを保持しているバッファです。有効なデータはバイト配列の長さより小さい可能性があります。
    * @param offset  `payload` 内のデータ開始位置
    * @param length  `payload` 内のデータの大きさ
    * @param eof     このブロックがストリームの終端を表す場合 true
    * @throws IllegalArgumentException パラメータの一つが不正な場合
    * @author Takami Torao
    */
  final case class Block(pipeId:Short, loss:Byte, @Nonnull payload:Array[Byte], offset:Int, length:Int, eof:Boolean) extends Message {
    Objects.requireNonNull(payload, "payload shouldn't be null")
    if(offset + length > payload.length) {
      throw new IllegalArgumentException("buffer overrun: offset=" + offset + ", length=" + length + ", actual=" + payload.length)
    }
    if(offset < 0 || length < 0) {
      throw new IllegalArgumentException("negative value: offset=" + offset + ", length=" + length)
    }
    if(length == 0) {
      throw new IllegalArgumentException(s"zero payload")
    }
    if(length > Block.MaxPayloadSize) {
      throw new IllegalArgumentException(s"too long payload: $length, max=${Block.MaxPayloadSize}")
    }
    if(loss < 0) {
      throw new IllegalArgumentException("invalid loss-rate: " + loss)
    }

    /**
      * このブロックのペイロードを ByteBuffer として参照します。オフセットの指定により初期状態のポジションが 0 でない可能性が
      * あります。
      *
      * @return ペイロードの ByteBuffer
      */
    @Nonnull
    def toByteBuffer:ByteBuffer = ByteBuffer.wrap(payload, offset, length)

    /**
      * このブロックのペイロードを UTF-8 でデコードした文字列として参照します。
      *
      * @return ペイロードを UTF-8 でデコードした文字列
      */
    @Nonnull
    def getString:String = getString(Asterisque.UTF8)

    /**
      * このブロックのペイロードを指定された文字セットでエンコードされた文字列として参照します。
      *
      * @param charset ペイロードの文字セット
      * @return ペイロードの文字列表現
      */
    @Nonnull
    def getString(@Nonnull charset:Charset) = new String(payload, offset, length, charset)

    /**
      * このインスタンスを文字列化します。
      */
    @Nonnull
    override def toString:String = {
      s"Block($pipeId,0x${Hex.encodeHexString(ByteBuffer.wrap(payload, offset, length))},$loss${if(eof) ",EOF" else ""})"
    }
  }

  object Block {

    /**
      * `Block` のペイロードに設定できる最大サイズです。0xEFFF (61,439バイト) を表します。
      */
    var MaxPayloadSize:Int = 0xFFFF - (4 * 1024)

    /**
      * 損失率付きの Block メッセージを構築します。
      *
      * @param pipeId  宛先のパイプ ID
      * @param loss    このブロックの損失率 (0-127)
      * @param payload このブロックのペイロード
      * @param offset  `payload` 内のデータ開始位置
      * @param length  `payload` 内のデータの大きさ
      * @throws IllegalArgumentException パラメータの一つが不正な場合
      */
    @Nonnull
    def apply(pipeId:Short, loss:Byte, @Nonnull payload:Array[Byte], offset:Int, length:Int):Block = {
      Block(pipeId, loss, payload, offset, length, eof = false)
    }

    /**
      * 通常の Block メッセージを構築します。損失率は 0 に設定されます。
      *
      * @param pipeId  宛先のパイプ ID
      * @param payload このブロックのペイロード
      * @param offset  `payload` 内のデータ開始位置
      * @param length  `payload` 内のデータの大きさ
      * @throws IllegalArgumentException パラメータの一つが不正な場合
      */
    @Nonnull
    def apply(pipeId:Short, @Nonnull payload:Array[Byte], offset:Int, length:Int):Block = {
      Block(pipeId, 0.toByte, payload, offset, length, eof = false)
    }

    /**
      * 指定されたパイプ ID に対する EOF ブロックを構築します。
      *
      * @param pipeId 宛先のパイプ ID
      * @return EOF ブロック
      */
    @Nonnull
    def eof(pipeId:Short) = new Block(pipeId, 0.toByte, Asterisque.Empty.Bytes, 0, 0, true)
  }

  /**
    * Control はフレームワークによって他のメッセージより優先して送信される制御メッセージを表します。
    * [[Message.pipeId]] は無視されます。
    *
    * @param data 制御メッセージのデータ
    * @author Takami Torao
    */
  final case class Control(@Nonnull data:Control.Fields) extends Message {
    Objects.requireNonNull(data, "fields is null")

    override val pipeId:Short = 0
  }

  object Control {
    /**
      * 通信を開始したときにピア間の設定を同期するための制御コードです。バイナリストリームの先頭で `*Q` として出現するように
      * [[io.asterisque.core.codec.MessageFieldCodec.Msg#Control]] が `*`、`SyncSession` が `Q` の値を取ります。
      * SyncSession を持つ制御メッセージのバイナリフィールドはヘルパークラス
      * [[SyncSession]] 経由で参照することができます。
      */
    val SyncSession:Byte = 'Q'

    /**
      * セッションの終了を表す制御コードです。この制御メッセージに有効なデータは付随しません。
      */
    val Close:Byte = 'C'

    trait Fields

    case object CloseField extends Fields

    val CloseMessage:Control = Control(CloseField)

  }

}