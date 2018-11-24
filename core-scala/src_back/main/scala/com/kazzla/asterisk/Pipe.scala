/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import org.slf4j.LoggerFactory
import java.io.{IOException, InputStream, OutputStream}
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.Promise
import com.kazzla.asterisk.async.Source
import io.asterisque.Export

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Pipe
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * function に対する呼び出し状態を表すクラスです。function の呼び出し開始から終了までのスコープを持ち、その呼び
 * 出し結果は `pipe.future` を使用して非同期で参照することができます。またパイプは非同期メッセージングにおける
 * メッセージの送出先/流入元を表しており同期ストリーミングもパイプを経由して行います。
 *
 * パイプの ID は `Session` を開始した側 (クライアント側) が最上位ビット 0、相手からの要求を受け取った側 (サー
 * ビス側) が 1 を持ちます。このルールは通信の双方で相手側との合意手続きなしに重複しないユニークなパイプ ID を
 * 発行することを目的としています。このため一つのセッションで同時に行う事が出来る呼び出しは最大で 32,768、パイプを
 * 共有しているピアの双方で 65,536 個までとなります。
 *
 * @param id パイプ ID
 * @param function このパイプの呼び出し先 function 番号
 * @param session このパイプのセッション
 * @author Takami Torao
 */
class Pipe private[asterisk](val id:Short, val function:Short, val session:Session) extends Attributes {
  import Pipe.logger

  /**
   * このパイプに対する受信処理を設定する前に受信したメッセージのキューです。
   */
  private val premature = new LinkedBlockingQueue[Message]()
  private val messagePump = new AtomicBoolean(false)
  private[asterisk] def dispatch(msg:Message):Unit = {
    if(messagePump.get()){
      session.dispatch(this, msg)
    } else premature.synchronized {
      if(messagePump.get()){
        session.dispatch(this, msg)
      } else {
        premature.put(msg)
      }
    }
  }

  private[asterisk] def startMessagePump():Unit = premature.synchronized {
    while(! premature.isEmpty){
      session.dispatch(this, premature.take())
    }
    messagePump.set(true)
  }

  /**
   * このパイプがクローズされているかどうかを表すアトミックなフラグ。
   */
  private[this] val closed = new AtomicBoolean(false)

  /**
   * このパイプがクローズされているときに true を返します。
   */
  def isClosed:Boolean = closed.get()

  /**
   * このパイプにブロックが到着した時に呼び出すイベントハンドラ。
   * アプリケーションはこのハンドラではなく [[src]] を使用する。
   */
  private[asterisk] val onBlock = new EventHandlers[Block]()

  /**
   * このパイプがクローズされたときに呼び出すイベントハンドラ。
   * アプリケーションはこのハンドラではなく [[future]] を使用する。
   */
  private[this] val onClosing = new EventHandlers[Boolean]()

  /**
   * このパイプがクローズされて確定した結果を通知するための `Promise`。
   */
  private[this] val promise = Promise[Any]()

  /**
   * このパイプによる双方の処理で確定した結果 (成功/失敗にかかわらず) を参照するための `Future` です。
   * パイプの結果が確定した時点でパイプはクローズされています。
   */
  lazy val future = promise.future

  /**
   * どのパイプで何が起きたかをトレースするためのログ出力用のシンボル文字列。
   */
  private[asterisk] val signature = s"#${if(session.wire.isServer) "S" else "C"}${id & 0xFFFF}"
  Pipe.logger.trace(s"$signature: pipe created")

  // ==============================================================================================
  // Open メッセージの送信
  // ==============================================================================================
  /**
   * このパイプが示す function 番号に対して指定された引数で Open メッセージを送信します。
   * @param params function 呼び出し時の引数
   */
  private[asterisk] def open(params:Seq[Any]):Unit = {
    Pipe.logger.trace(s"$signature: sending open")
    val open = Open(id, function, params)
    session.post(open)
  }

  // ==============================================================================================
  // ブロックの送信
  // ==============================================================================================
  /**
   * 指定されたバイナリデータを非同期メッセージングのメッセージとして Block を送信します。
   */
  private[this] def block(buffer:Array[Byte], offset:Int, length:Int):Unit = block(Block(id, buffer, offset, length))

  // ==============================================================================================
  // ブロックの受信
  // ==============================================================================================
  /**
   * 指定された Block メッセージを非同期メッセージングのメッセージとして送信します。
   */
  private[this] def block(block:Block):Unit = {
    if(logger.isTraceEnabled){ logger.trace(s"$signature: sending block: $block") }
    session.post(block)
  }

  // ==============================================================================================
  // パイプのクローズ
  // ==============================================================================================
  /**
   * 指定された結果で Close メッセージを送信しパイプを閉じます。
   * このメソッドは正常に結果が確定したときの動作です。
   * @param result Close に付加する結果
   */
  private[asterisk] def close(result:Any):Unit = if(closed.compareAndSet(false, true)){
    onClosing(true)
    session.post(Close(id, Right(result)))
    promise.success(result)
    session.destroy(id)
    Pipe.logger.trace(s"$signature: pipe is closed with success: $result")
  } else {
    Pipe.logger.debug(s"$signature: pipe already closed: $result")
  }

  // ==============================================================================================
  // パイプのクローズ
  // ==============================================================================================
  /**
   * 指定した例外で Close を送信しパイプを閉じます。
   * このメソッドはエラーが発生したときの動作です。
   * @param ex Close に付加する例外
   */
  private[asterisk] def close(ex:Throwable):Unit = if(closed.compareAndSet(false, true)){
    onClosing(true)
    session.post(Close.unexpectedError(id, ex))
    promise.failure(ex)
    session.destroy(id)
    Pipe.logger.trace(s"$signature: pipe is closed with failure: $ex")
  } else {
    Pipe.logger.debug(s"$signature: pipe already closed: $ex")
  }

  // ==============================================================================================
  // パイプのクローズ
  // ==============================================================================================
  /**
   * 相手側から受信した Close メッセージによってこのパイプを閉じます。
   */
  private[asterisk] def close(close:Close):Unit = if(closed.compareAndSet(false, true)){
    onClosing(false)
    if(close.result.isLeft){
      promise.failure(close.result.left.get)
    } else {
      promise.success(close.result.right.get)
    }
    session.destroy(id)
    Pipe.logger.trace(s"$signature: pipe is closed by peer: $close")
  } else {
    Pipe.logger.debug(s"$signature: pipe already closed: $close")
  }

  /**
   * 非同期メッセージングの Block メッセージ受信を行うメッセージソース。
   */
  private[this] val _src = new Pipe.MessageSource()

  // ブロックを受信したらメッセージソースに通知
  onBlock ++ _src

  /**
   * このパイプによる非同期メッセージングの受信処理を設定する非同期コレクションです。
   * この非同期コレクションは処理の呼び出しスレッド内でしか受信処理を設定することが出来ません。
   */
  val src:Source[Block] = _src

  /**
   * このパイプに対する非同期メッセージングの送信を行うメッセージシンクです。
   * クローズされていないパイプに対してであればどのようなスレッドからでもメッセージの送信を行うことが出来ます。
   */
  val sink = new MessageSink()

  /**
   * このパイプに対するブロック出力を `OutputStream` として行います。ストリームに対する出力データがバッファ
   * サイズに達するか `flush()` が実行されると [[Block]] として送信されます。
   * このストリームへの出力操作は I/O ブロックを受けません。
   */
  lazy val out:OutputStream = {
    val _out = new PipeOutputStream(Pipe.this)
    onClosing ++ { me => if(me) _out.close() else _out.emergencyClose() }
    _out
  }

  /**
   * このパイプが受信したブロックを `InputStream` として参照します。
   * @@[[Export]](stream=true) 宣言されているメソッドのパイプのみ使用することが
   * 出来ます。
   */
  private[this] var _in:Option[PipeInputStream] = None

  /**
   * このパイプがメッセージとして受信したバイナリデータを `InputStream` として参照します。
   * 非同期で到着するメッセージを同期ストリームで参照するために、入力ストリームを使用する前に [[useInputStream()]]
   * を呼び出して内部的なキューを準備しておく必要があります。[[useInputStream()]] を行わず入力ストリームを参照
   * した場合は例外となります。
   */
  lazy val in:InputStream = _in match {
    case Some(i) => i
    case None => throw new IOException(s"$signature: useInputStream() is not declared on pipe")
  }

  /**
   * 非同期で到着するバイナリーデータを [[in]] を使用した同期ストリームとして参照するためのキューを準備します。
   * このメソッドを呼び出すと受信したブロックのバッファリグが行われますので、入力ストリームを使用する場合のみ
   * 呼び出してください。
   */
  def useInputStream():Unit = {
    Pipe.assertInCall("useInputStream() must be call in caller thread, Ex. session.open(10){_.useInputStream()}, 10.accept{withPipe{pipe=>pipe.useInputStream();...}}")
    val is = new PipeInputStream(signature)
    src.foreach(is)
    _in = Some(is)
    onClosing ++ { me => if(me) is.close() else if(!is.isClosed){ is(Block.eof(id)) } }
    logger.trace(s"$signature: prepare internal buffer for messaging that is used for InputStream")
  }

  // ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
  // MessageSink
  // ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
  /**
   */
  class MessageSink private[Pipe]() {

    // ============================================================================================
    // ブロックの送信
    // ============================================================================================
    /**
     * メッセージの終了を示す EOF メッセージを非同期メッセージとして送信します。
     */
    def sendEOF():MessageSink = {
      block(Block.eof(Pipe.this.id))
      this
    }

    // ============================================================================================
    // ブロックの送信
    // ============================================================================================
    /**
     * 指定されたバイナリデータを非同期メッセージとして送信します。
     * このメソッドは渡された buffer をそのまま送信バッファとして使用します。従ってメソッド呼び出しが終わった後に
     * バッファの内容を変更した場合、実際に送信されるデータは保証されません。
     */
    def sendDirect(buffer:Array[Byte]):MessageSink = {
      sendDirect(buffer, 0, buffer.length)
      this
    }

    // ============================================================================================
    // ブロックの送信
    // ============================================================================================
    /**
     * 指定されたバイナリデータを非同期メッセージとして送信します。
     * このメソッドは渡された buffer をそのまま送信バッファとして使用します。従ってメソッド呼び出しが終わった後に
     * バッファの内容を変更した場合、実際に送信されるデータは保証されません。
     */
    def sendDirect(buffer:Array[Byte], offset:Int, length:Int):MessageSink = {
      Pipe.this.block(buffer, offset, length)
      this
    }

    // ============================================================================================
    // ブロックの送信
    // ============================================================================================
    /**
     * 指定されたバイナリデータを非同期メッセージとして送信します。
     * このメソッドは渡された buffer をそのまま送信バッファとして使用します。従ってメソッド呼び出しが終わった後に
     * バッファの内容を変更した場合、実際に送信されるデータは保証されません。
     */
    def <<! (buffer:Array[Byte]):MessageSink = {
      sendDirect(buffer)
      this
    }

    // ============================================================================================
    // ブロックの送信
    // ============================================================================================
    /**
     * 指定されたバイナリデータを非同期メッセージとして送信します。
     */
    def send(buffer:ByteBuffer):MessageSink = {
      val payload = new Array[Byte](buffer.remaining())
      buffer.get(payload)
      sendDirect(payload)
      this
    }

    // ============================================================================================
    // ブロックの送信
    // ============================================================================================
    /**
     * 指定されたバイナリデータを非同期メッセージとして送信します。
     */
    def send(buffer:Array[Byte]):MessageSink = {
      send(buffer, 0, buffer.length)
      this
    }

    // ============================================================================================
    // ブロックの送信
    // ============================================================================================
    /**
     * 指定されたバイナリデータを非同期メッセージとして送信します。
     */
    def send(buffer:Array[Byte], offset:Int, length:Int):MessageSink = {
      val payload = new Array[Byte](length)
      System.arraycopy(buffer, offset, payload, 0, length)
      sendDirect(payload, 0, length)
      this
    }

    // ============================================================================================
    // ブロックの送信
    // ============================================================================================
    /**
     * 指定されたバイナリデータを非同期メッセージとして送信します。
     */
    def << (buffer:Array[Byte]):MessageSink = {
      send(buffer)
      this
    }
    def << (buffer:ByteBuffer):MessageSink = {
      send(buffer)
      this
    }
  }
}

object Pipe {
  private[Pipe] val logger = LoggerFactory.getLogger(classOf[Pipe])

  /**
   * スレッドに結びつけられたパイプを参照するためのスレッドローカル。
   */
  private[this] val pipes = new ThreadLocal[Pipe]()

  /**
   * 現在のスレッドにむつび付けられているパイプを参照します。
   */
  def apply():Option[Pipe] = Option(pipes.get())

  /**
   * 現在のスレッドにパイプが関連づけられていない場合に指定されたメッセージ付きの例外を発生します。
   * @param msg 例外メッセージ
   */
  private[asterisk] def assertInCall(msg:String):Unit = {
    if(pipes.get() == null){
      throw new IllegalStateException(msg)
    }
  }

  def orElse[T](default: =>T)(f:(Pipe)=>T):T = apply() match {
    case Some(pipe) => f(pipe)
    case None => default
  }

  private[asterisk] def using[T](pipe:Pipe)(f: =>T):T = {
    val old = pipes.get()
    pipes.set(pipe)
    try {
      f
    } finally {
      pipes.set(old)
    }
  }

  /**
   * [[com.kazzla.asterisk.Wire.isServer]] が true の通信端点側で新しいパイプ ID を発行するときに立てる
   * ビット。
   */
  private[asterisk] val UNIQUE_MASK:Short = (1 << 15).toShort

  // ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
  // MessageSource
  // ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
  /**
   * パイプに対してブロック送受信 (ストリーム) 操作をおこなうためのクラス。
   * メッセージ配信スレッド内でハンドラを設定する必要があります。
   */
  private[Pipe] class MessageSource private[Pipe]() extends Source[Block] with ((Block)=>Unit) {

    /**
     * Block メッセージ受信時に呼び出される処理。このインスタンスに設定されているコンビネータ (関数) に転送する。
     * @param block 受信したメッセージ
     */
    def apply(block:Block):Unit = synchronized {
      sequence(block)
      if(block.eof){
        finish()
      }
    }

    /**
     * コンビネータの設定を行えるかの評価。パイプが参照できないスレッドの場合は例外となる。
     */
    override def onAddOperation() = {
      Pipe.assertInCall("operation for message passing can only define in caller thread")
      super.onAddOperation()
    }
  }

}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// PipeInputStream
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * パイプ上で受信した Block メッセージのバイナリデータをストリームとして参照するためのクラス。
 */
private class PipeInputStream private[asterisk](signature:String) extends InputStream with ((Block) => Unit) {
  import PipeInputStream.logger

  /**
   * パイプから受信したブロックを Block として保持するためのキュー。
   */
  private[this] val receiveQueue = new LinkedBlockingQueue[Block]()

  /**
   * 読み出し中の ByteBuffer。
   */
  private[this] var processing:Option[ByteBuffer] = None

  /**
   * ストリーム上で EOF を検知したかのフラグ。
   */
  private[this] var eof = false

  /**
   * インスタンスの利用側によってクローズされたかのフラグ。
   */
  private[this] var closed = false
  def isClosed = closed

  def apply(block:Block):Unit = if(! closed){
    if(logger.isTraceEnabled){
      logger.trace(s"$signature: apply($block) enqueueing specified block message as stream data")
    }
    receiveQueue.put(block)
  }

  def read():Int = processingBuffer match {
    case None => -1
    case Some(buffer) =>
      ensureOpen()
      buffer.get() & 0xFF
  }

  override def read(b:Array[Byte]):Int = read(b, 0, b.length)

  override def read(b:Array[Byte], offset:Int, length:Int):Int = processingBuffer match {
    case None => -1
    case Some(buffer) =>
      ensureOpen()
      val len = math.min(length, buffer.remaining())
      buffer.get(b, offset, len)
      len
  }

  override def close():Unit = {
    closed = true
    PipeInputStream.logger.trace(s"$signature: close()")
  }

  private[this] def processingBuffer:Option[ByteBuffer] = {
    if(eof){
      None
    } else if(processing.isDefined && processing.get.hasRemaining){
      processing
    //} else if(receiveQueue.isEmpty){
    //  None
    } else {
      val block = receiveQueue.take()
      if(block.isEOF){
        if(logger.isTraceEnabled){
          logger.trace(s"$signature: eof detected, closing stream")
        }
        eof = true   // EOF 検出
        None
      } else {
        if(logger.isTraceEnabled){
          logger.trace(s"$signature: dequeue block message for stream: $block")
        }
        processing = Some(block.toByteBuffer)
        processing
      }
    }
  }
  private[this] def ensureOpen():Unit = if(closed){
    throw new IOException("stream closed")
  }
}
private object PipeInputStream {
  private[PipeInputStream] val logger = LoggerFactory.getLogger(classOf[PipeInputStream])
}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// PipeOutputStream
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * パイプに対してバイナリデータを Block メッセージとして送信するための出力ストリームです。出力されたバイナリは内部
 * でバッファリングされ、フラグメント化されたブロックとして送信されます。
 * バッファ容量に達した場合やフラッシュされた場合はバッファの内容を Block メッセージにカプセル化して非同期で
 * セッションへ送信します。
 */
private class PipeOutputStream private[asterisk](pipe:Pipe, bufferSize:Int) extends OutputStream {
  import PipeOutputStream.logger
  def this(pipe:Pipe) = this(pipe, 4 * 1024)
  private[this] var buffer = ByteBuffer.allocate(bufferSize)
  private[this] var osClosed = false
  /**
   * 出力バッファサイズを変更します。バッファリングしているデータはすべて [[flush()]] されます。
   * @param size 出力バッファサイズ
   */
  def setBufferSize(size:Int):Unit = {
    flush()
    assert(buffer.position() == 0)
    buffer = ByteBuffer.allocate(size)
  }
  def write(b:Int):Unit = {
    ensureWrite(1)
    buffer.put(b.toByte)
  }
  override def write(b:Array[Byte]):Unit = {
    ensureWrite(b.length)
    buffer.put(b)
  }
  override def write(b:Array[Byte], offset:Int, length:Int):Unit = {
    ensureWrite(length)
    buffer.put(b, offset, length)
  }
  private[this] def ensureWrite(len:Int):Unit = {
    if(osClosed){
      throw new IOException(s"${pipe.signature}: unable to write to closed pipe or stream: ${pipe.id}")
    }
    // TODO バッファサイズより大きなデータが書き込めない?
    if(buffer.position() + len > buffer.capacity()){
      flush()
    }
  }
  override def flush():Unit = {
    if(logger.isTraceEnabled){
      logger.trace(s"${pipe.signature}: flush()")
    }
    if(buffer.position() > 0){
      buffer.flip()
      while(buffer.hasRemaining){
        val len = math.min(buffer.remaining(), Block.MaxPayloadSize)
        val b = new Array[Byte](len)
        buffer.get(b, 0, b.length)
        pipe.sink.send(b)
      }
      buffer.clear()
    }
  }
  override def close():Unit = if(! osClosed){
    flush()
    pipe.sink.sendEOF()
    osClosed = true
    PipeOutputStream.logger.trace(s"${pipe.signature}: close()")
  }
  def emergencyClose():Unit = {
    osClosed = true
    PipeOutputStream.logger.trace(s"${pipe.signature}: output stream closed by peer")
  }
}

private object PipeOutputStream {
  private[PipeOutputStream] val logger = LoggerFactory.getLogger(classOf[PipeOutputStream])
}