package io.asterisque.wire.rpc

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

import io.asterisque.utils.{Debug, Latch}
import io.asterisque.wire.message.Message.{Block, Close, Open}
import io.asterisque.wire.message.Message
import io.asterisque.wire.rpc.Pipe._
import javax.annotation.Nonnull
import org.slf4j.LoggerFactory

import scala.concurrent.{Future, Promise}

/**
  * function に対する呼び出し状態を表すクラスです。function の呼び出し開始から終了までのスコープを持ち、その呼び
  * 出し結果は `Pipe.future` を使用して非同期で参照することができます。またパイプは非同期メッセージングにおける
  * メッセージの送出先/流入元を表しており同期ストリーミングもパイプを経由して行います。
  *
  * パイプの ID は `Session` を開始した側 (クライアント側) が最上位ビット 0、相手からの要求を受け取った側 (サー
  * ビス側) が 1 を持ちます。このルールは通信の双方で相手側との合意手続きなしに重複しないユニークなパイプ ID を
  * 発行することを目的としています。このため一つのセッションで同時に行う事が出来る呼び出しは最大で 32,768、パイプを
  * 共有しているピアの双方で 65,536 個までとなります。
  *
  * @param open このパイプを作成するときに使用した [[io.asterisque.wire.message.Message.Open]] メッセージ
  */
class Pipe private[rpc](@Nonnull val open:Open, @Nonnull stub:Pipe.Stub) {

  /**
    * このパイプがクローズされているかどうかを表すフラグ。
    */
  private[this] val closed = new AtomicBoolean(false)

  /**
    * パイプの処理結果を設定する Promise です。
    */
  private[this] val promise = Promise[Any]()

  /**
    * このパイプを同一セッション内で識別するための ID です。
    */
  def id:Short = open.pipeId

  def function:Short = open.functionId

  def priority:Byte = open.priority

  logger.trace(s"$this: pipe $id for function $function with priority $priority created")

  /**
    * このパイプによる双方の処理で確定した結果 (成功/失敗にかかわらず) を参照するための Future です。
    * パイプの結果が確定した時点でパイプはクローズされています。
    */
  val future:Future[Any] = promise.future

  /**
    * このパイプがクローズされているかを参照します。
    *
    * @return すでにクローズされている場合 true
    */
  def isClosed:Boolean = closed.get

  /**
    * このパイプが示す function 番号に対して指定された引数で Open メッセージを送信します。
    *
    * @param params function 呼び出し時の引数
    */
  private[rpc] def open(params:Array[Byte]):Unit = {
    logger.trace("{}: sending open", this)
    val open = Open(id, priority, function, params)
    stub.post(open)
  }

  /**
    * エラーメッセージ付きの Close を送信しパイプを閉じます。このメソッドはエラーが発生したときの動作です。
    *
    * @param code エラーコード
    * @param msg  エラーメッセージ
    */
  private[rpc] def closeWithError(code:Byte, @Nonnull msg:String):Unit = {
    close(Close.withFailure(id, code, msg))
  }

  /**
    * 指定された Close メッセージでこのパイプと peer のパイプをクローズします。
    *
    * @param close Close メッセージ
    */
  private[this] def close(@Nonnull close:Close):Unit = if(closed.compareAndSet(false, true)) {
    stub.post(close)
    close.toEither match {
      case Right(result) =>
        promise.success(result)
        logger.trace(s"$this: pipe was closed successfully: ${Debug.toString(result)}")
      case Left((code, msg)) =>
        promise.failure(new Abort(code, msg))
        logger.trace(s"$this: pipe was closed with failure: [$code] $msg")
    }
    stub.closed(this)
  } else {
    logger.debug(s"$this: pipe has already been closed: $close")
  }

  /**
    * peer から受信した [[Close]] メッセージによってこのパイプを閉じます。
    *
    * @param close 受信した Close メッセージ
    */
  private[rpc] def closePassively(close:Close):Unit = if(closed.compareAndSet(false, true)) {
    close.toEither match {
      case Right(result) =>
        promise.success(result)
        logger.trace(s"$this: closePassively($close): success: ${Debug.toString(result)}")
      case Left((code, msg)) =>
        promise.failure(new Abort(code, msg))
        logger.trace(s"$this: closePassively($close): failure: [$code] $msg")
    }
    stub.closed(this)
    logger.trace(s"$this: pipe is closed by peer: $close")
  } else {
    logger.trace(s"$this: pipe is already been closed: $close")
  }

  /**
    * 指定されたバイナリデータを非同期メッセージングのメッセージとして Block を送信します。
    *
    * @see PipeMessageSink
    */
  private[rpc] def block(buffer:Array[Byte], offset:Int, length:Int):Unit = {
    block(Block(id, buffer, offset, length))
  }

  /**
    * 指定された Block メッセージを非同期メッセージングのメッセージとして送信します。
    *
    * @see PipeMessageSink
    */
  private[rpc] def block(block:Block):Unit = {
    if(logger.isTraceEnabled) {
      logger.trace(s"$this: sending block: $block")
    }
    stub.post(block)
  }

  /**
    * 任意のバイナリを送信できる同期ストリームを参照します。出力データが内部的なバッファの上限に達するか、出力ストリームの
    * `flush()` が実行されるとバッファの内容が [[Block]] として非同期で送信されます。
    * 返値のストリームはマルチスレッドの出力に対応していません。
    *
    * 通信相手が Block によるストリーミングを許可していない場合、不正なブロック受信によってパイプがクローズされる可能性が
    * あります。
    */
  @Nonnull
  lazy val out:java.io.OutputStream = new Pipe.OutputStream(this, 4 * 1024)

  /**
    * @return このインスタンスの文字列
    */
  @Nonnull
  override def toString:String = f"${stub.id}%s#${id & 0xFFFF}%04X"

}

object Pipe {
  private val logger = LoggerFactory.getLogger(classOf[Pipe])

  /**
    * [[Session.isPrimary]] に true を持つ通信端点側で新しいパイプ ID を発行するときに立てるビットフラグです。
    */
  val UniqueMask:Short = (1 << 15).toShort

  private[rpc] trait Stub {
    @Nonnull
    def id:String

    def post(@Nonnull msg:Message):Unit

    def closed(@Nonnull pipe:Pipe):Unit
  }

  /**
    * パイプに対してバイナリデータを Block メッセージとして送信するための出力ストリームです。出力されたバイナリは内部
    * でバッファリングされ、フラグメント化されたブロックとして送信されます。
    * バッファ容量に達した場合やフラッシュされた場合はバッファの内容を Block メッセージにカプセル化して非同期で
    * セッションへ送信します。
    */
  private[Pipe] class OutputStream(val pipe:Pipe, val bufferSize:Int) extends java.io.OutputStream {
    private[this] val barrier = new Latch()
    private[this] var buffer = ByteBuffer.allocate(bufferSize)
    private[this] var closed = false

    /**
      * 出力バッファサイズを変更します。バッファリングしているデータはすべて [[flush()]] されます。
      *
      * @param size 出力バッファサイズ
      */
    @throws[IOException]
    def setBufferSize(size:Int):Unit = {
      flush()
      assert(buffer.position() == 0)
      buffer = ByteBuffer.allocate(size)
    }

    @throws[IOException]
    override def write(b:Int):Unit = {
      ensureWrite(1)
      write(() => buffer.put(b.toByte))
    }

    @throws[IOException]
    override def write(b:Array[Byte]):Unit = {
      write(b, 0, b.length)
    }

    @throws[IOException]
    override def write(b:Array[Byte], offset:Int, length:Int):Unit = {
      ensureWrite(length)
      write(() => buffer.put(b, offset, length))
    }

    @throws[IOException]
    private def write(exec:Runnable):Unit = try {
      barrier.exec(exec)
    } catch {
      case ex:InterruptedException =>
        throw new IOException("write operation interrupted", ex)
    }

    @throws[IOException]
    private def ensureWrite(len:Int):Unit = {
      if(closed) {
        //      throw new IOException(pipe + ": unable to write to wsClosed pipe or stream: " + pipe.id);
      }
      // TODO バッファサイズより大きなデータが書き込めない?
      if(buffer.position() + len > buffer.capacity) {
        flush()
      }
    }

    override def flush():Unit = {
      if(logger.isTraceEnabled) {
        logger.trace(pipe + ": flush()")
      }
      if(buffer.position() > 0) {
        buffer.flip
        while(buffer.hasRemaining) {
          val len = Math.min(buffer.remaining, Block.MaxPayloadSize)
          val b = new Array[Byte](len)
          buffer.get(b, 0, b.length)
          //        pipe.sink.send(b);
        }
        buffer.clear
      }
    }

    override def close():Unit = {
      if(!closed) {
        flush()
        //      pipe.sink.sendEOF();
        closed = true
        logger.trace(pipe + ": lock()")
      }
    }

    def emergencyClose():Unit = {
      closed = true
      logger.trace(s"$pipe: output stream wsClosed by peer")
    }
  }

}
