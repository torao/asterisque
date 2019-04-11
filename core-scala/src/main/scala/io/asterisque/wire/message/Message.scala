package io.asterisque.wire.message

import java.io.Serializable
import java.nio.ByteBuffer
import java.nio.charset.{Charset, StandardCharsets}
import java.util
import java.util.Objects

import javax.annotation.{Nonnull, Nullable}
import org.apache.commons.codec.binary.Hex

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

  /**
    * 指定されたオブジェクトとこのインスタンスが等しい場合 true を返します。
    *
    * @param obj 比較するオブジェクト
    * @return 等しい場合 true
    */
  override def equals(obj:Any):Boolean = obj match {
    case other:Message => this.pipeId == other.pipeId
    case _ => false
  }

  /**
    * このインスタンスのハッシュ値を参照します。
    *
    * @return ハッシュ値
    */
  override def hashCode():Int = pipeId
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

    /**
      * 指定されたオブジェクトとこのインスタンスが等しい場合 true を返します。
      *
      * @param obj 比較するオブジェクト
      * @return 等しい場合 true
      */
    override def equals(obj:Any):Boolean = super.equals(obj) && (obj match {
      case other:Open =>
        this.priority == other.priority &&
          this.functionId == other.functionId &&
          util.Arrays.equals(this.params, other.params)
      case _ => false
    })

    /**
      * このインスタンスのハッシュ値を参照します。
      *
      * @return ハッシュ値
      */
    override def hashCode():Int = {
      Message.hashCode(super.hashCode(), priority, functionId, Message.hashCode(params, 0, params.length))
    }
  }

  object Open {

    /**
      * デフォルトの優先度を持つ Open メッセージを構築します。
      *
      * @param pipeId     新しく要求するパイプの ID
      * @param functionId オープンを要求するファンクション ID
      * @param params     ファンクションの呼び出しパラメータ
      */
    def apply(pipeId:Short, functionId:Short, @Nonnull params:Array[Byte]):Open = Open(pipeId, 0, functionId, params)
  }

  /**
    * パイプのクローズを示すメッセージです。`code == 0` は処理の成功を示しており ` result` は処理依存の結果がシリアライズ
    * された形式で保存されています。`code` が 0 以外の値を示す場合はエラーによって処理が中断されたことを示しており
    * `result` はエラー状況を示すメッセージの UTF-8 書式です。
    *
    * @param pipeId パイプ ID
    * @param result Right(処理結果)、または Left(エラーメッセージ)
    */
  final case class Close(pipeId:Short, code:Byte, @Nonnull result:Array[Byte]) extends Message {
    Objects.requireNonNull(result)

    /**
      * この Close メッセージの結果を成功 `Right[Array[Byte]]` または失敗 `Left[(Byte,String)]` で参照します。
      *
      * @return 結果
      */
    def toEither:Either[(Byte, String), Array[Byte]] = if(code == Close.Code.SUCCESS) {
      Right(result)
    } else {
      Left((code, new String(result, StandardCharsets.UTF_8)))
    }

    /**
      * 指定されたオブジェクトとこのインスタンスが等しい場合 true を返します。
      *
      * @param obj 比較するオブジェクト
      * @return 等しい場合 true
      */
    override def equals(obj:Any):Boolean = super.equals(obj) && (obj match {
      case other:Close =>
        this.code == other.code && util.Arrays.equals(this.result, other.result)
      case _ => false
    })

    /**
      * このインスタンスのハッシュ値を参照します。
      *
      * @return ハッシュ値
      */
    override def hashCode():Int = Message.hashCode(super.hashCode(), code, util.Arrays.hashCode(result))
  }

  object Close {

    /**
      * 処理成功の Close メッセージを構築します。
      *
      * @param pipeId 宛先のパイプ ID
      * @param result 成功として渡される結果
      */
    def apply(pipeId:Short, @Nullable result:Array[Byte]):Close = {
      Objects.requireNonNull(result)
      Close(pipeId, Code.SUCCESS, result)
    }

    /**
      * 予期しない状態によってパイプを終了するときのクローズメッセージを作成します。
      *
      * @param pipeId 宛先のパイプ ID
      * @param msg    中断メッセージ
      * @return 予期しない状況による処理中断を表すクローズメッセージ
      */
    def withFailure(pipeId:Short, @Nonnull msg:String):Close = withFailure(pipeId, Code.UNEXPECTED, msg)

    /**
      * 指定されたエラーコードによってパイプを終了するときのクローズメッセージを作成します。
      *
      * @param pipeId 宛先のパイプ ID
      * @param code   エラーコード
      * @param msg    エラーメッセージ
      * @return エラーによる処理中断を表すクローズメッセージ
      */
    @Nonnull
    def withFailure(pipeId:Short, code:Byte, @Nonnull msg:String):Close = {
      Objects.requireNonNull(msg)
      if(code == Code.SUCCESS) {
        throw new IllegalArgumentException(s"failure result with SUCCESS code: [$code] $msg")
      }
      Close(pipeId, code, msg.getBytes(StandardCharsets.UTF_8))
    }

    object Code {

      /** 処理が成功したことを表すコード。 */
      val SUCCESS:Byte = 0

      /** 予期しない状況によって処理が中断したことを示すコード。 */
      val UNEXPECTED:Byte = -1

      /** セッションがクローズ処理に入ったため処理が中断されたことを示すコード。 */
      val SESSION_CLOSING:Byte = -2

      /** サービスが見つからない事を示すコード。 */
      val SERVICE_UNDEFINED:Byte = 100

      /**
        * サービスに function id が定義されていないことを示すコード。
        */
      val FUNCTION_UNDEFINED:Byte = 101

      /**
        * function の実行に失敗したことを示すコード。
        */
      val FUNCTION_FAILED:Byte = 102

      /**
        * ブロックを受信できない function にブロックが送信されたことを示すコード。
        */
      val FUNCTION_CANNOT_RECEIVE_BLOCK:Byte = 103

      /**
        * 宛先のパイプが存在しない事を示すコード。
        */
      val DESTINATION_PIPE_UNREACHABLE:Byte = 104

    }

  }

  /**
    * [[io.asterisque.wire.rpc.Pipe]] を経由して双方向で交換可能なメッセージです。パイプ間での双方向ストリーミングのために使用されます。
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
    def getString:String = getString(StandardCharsets.UTF_8)

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

    /**
      * 指定されたオブジェクトとこのインスタンスが等しい場合 true を返します。
      *
      * @param obj 比較するオブジェクト
      * @return 等しい場合 true
      */
    override def equals(obj:Any):Boolean = super.equals(obj) && (obj match {
      case other:Block =>
        this.loss == other.loss &&
          util.Arrays.equals(this.payload, this.offset, this.offset + this.length, other.payload, other.offset, other.offset + other.length) &&
          this.eof == other.eof
      case _ => false
    })

    /**
      * このインスタンスのハッシュ値を参照します。
      *
      * @return ハッシュ値
      */
    override def hashCode():Int = {
      Message.hashCode(super.hashCode(), loss, Message.hashCode(payload, offset, length), if(eof) 1 else 0)
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
    def eof(pipeId:Short) = new Block(pipeId, 0.toByte, Array.empty, 0, 0, true)
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

    /**
      * 指定されたオブジェクトとこのインスタンスが等しい場合 true を返します。
      *
      * @param obj 比較するオブジェクト
      * @return 等しい場合 true
      */
    override def equals(obj:Any):Boolean = super.equals(obj) && (obj match {
      case other:Control => this.data == other.data
      case _ => false
    })

    /**
      * このインスタンスのハッシュ値を参照します。
      *
      * @return ハッシュ値
      */
    override def hashCode():Int = {
      Message.hashCode(super.hashCode(), data.hashCode())
    }
  }

  object Control {

    /**
      * 通信を開始したときにピア間の設定を同期するための制御コードです。SyncSession を持つ制御メッセージのバイナリフィールドは
      * ヘルパークラス [[SyncSession]] 経由で参照することができます。
      */
    val SyncSession:Byte = 'Q'

    /**
      * セッションの終了を表す制御コードです。この制御メッセージに有効なデータは付随しません。
      */
    val Close:Byte = 'C'

    trait Fields {
      def equals(obj:Any):Boolean

      def hashCode():Int
    }

    case object CloseField extends Fields

    val CloseMessage:Control = Control(CloseField)

  }

  /**
    * payload のハッシュ値を算出。[[util.Arrays.hashCode()]] では offset と length を指定できないため。
    *
    * @param payload バイト配列
    * @param offset  開始位置
    * @param length  長さ
    * @return
    */
  private def hashCode(payload:Array[Byte], offset:Int, length:Int):Int = {
    var result = 1
    for(i <- offset until (offset + length)) {
      result = 31 * result + payload(i)
    }
    result
  }

  private[message] def hashCode(values:Int*):Int = {
    var result = 1
    for(i <- values) {
      result = 31 * result + i
    }
    result
  }

}