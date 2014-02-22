/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import org.slf4j.LoggerFactory
import java.io.{IOException, OutputStream, InputStream}
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Promise
import com.kazzla.asterisk.async.Source

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Pipe
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * パイプ ID を生成する (つまり Open する) 場合、値の最上位ビットは `Session` を開始した側が 0、相手
 * からの要求を受け取った側が 1 を使用する。これは通信の双方で相手側との合意手順を取らずユニークな ID を生成する
 * ことを目的としている。
 * これはつまり相手から受信、または相手へ送信するデータのパイプ ID の該当ビットはこのパイプ ID のそれと逆転して
 * いなければならないことを意味する。
 *
 * @author Takami Torao
 */
class Pipe private[asterisk](val id:Short, val function:Short, val session:Session) extends Attributes {
	import Pipe.logger

	private[this] val closed = new AtomicBoolean(false)
	def isClosed:Boolean = closed.get()

	private[asterisk] val onBlock = new EventHandlers[Block]()
	private[this] val onClosing = new EventHandlers[Boolean]()

	private[this] val promise = Promise[Any]()
	lazy val future = promise.future

	private[asterisk] val signature = s"#${if(session.wire.isServer) "S" else "C"}${id & 0xFFFF}"
	Pipe.logger.trace(s"$signature: pipe created")

	// ==============================================================================================
	// ブロックの送信
	// ==============================================================================================
	/**
	 * 指定されたバイナリデータを Block として送信します。
	 */
	private[asterisk] def open(params:Seq[Any], f:()=>Unit):Unit = {
		Pipe.logger.trace(s"$signature: sending open")
		val open = Open(id, function, params)
		session.post(open, Some(f))
	}

	// ==============================================================================================
	// ブロックの送信
	// ==============================================================================================
	/**
	 * 指定されたバイナリデータを Block として送信します。
	 */
	private[this] def block(buffer:Array[Byte], offset:Int, length:Int):Unit = block(Block(id, buffer, offset, length))

	// ==============================================================================================
	// ブロックの受信
	// ==============================================================================================
	/**
	 * 指定された Block を受信したときに呼び出されます。
	 */
	private[this] def block(block:Block):Unit = {
		if(logger.isTraceEnabled){ logger.trace(s"$signature: sending block: $block") }
		session.post(block, None)
	}

	// ==============================================================================================
	// パイプのクローズ
	// ==============================================================================================
	/**
	 * 指定した result 付きで Close を送信しこのパイプを閉じます。
	 * @param result Close に付加する結果
	 */
	def close(result:Any):Unit = if(closed.compareAndSet(false, true)){
		onClosing(true)
		session.post(Close(id, Right(result)), None)
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
	 * 指定した例外付きで Close を送信しパイプを閉じます。
	 * @param ex Close に付加する例外
	 */
	def close(ex:Throwable):Unit = if(closed.compareAndSet(false, true)){
		onClosing(true)
		session.post(Close(id, Left(ex.toString)), None)
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
	 * 相手側から受信した Close によってこのパイプを閉じます。
	 */
	private[asterisk] def close(close:Close):Unit = if(closed.compareAndSet(false, true)){
		onClosing(false)
		if(close.result.isLeft){
			val ex = new RemoteException(close.result.left.get)
			promise.failure(ex)
		} else {
			promise.success(close.result.right.get)
		}
		session.destroy(id)
		Pipe.logger.trace(s"$signature: pipe is closed by peer: $close")
	} else {
		Pipe.logger.debug(s"$signature: pipe already closed: $close")
	}

	private[this] val _src = new Pipe.MessageSource()
	onBlock ++ _src

	val src:Source[Block] = _src

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
	 * @@[[com.kazzla.asterisk.Export]](stream=true) 宣言されているメソッドのパイプのみ使用することが
	 * 出来ます。
	 */
	private[this] var _in:Option[PipeInputStream] = None

	/**
	 * このパイプが受信したブロックを `InputStream` として参照します。
	 * @@[[com.kazzla.asterisk.Export]](stream=true) 宣言されているメソッドのパイプのみ使用することが
	 * 出来ます。
	 */
	lazy val in:InputStream = _in match {
		case Some(i) => i
		case None => throw new IOException(s"$signature: useInputStream() is not declared on pipe")
	}

	/**
	 * パイプの入力ストリーム [[in]] を使用するために
	 * このメソッドを呼び出すと受信したブロックのバッファリグが行われますので、入力ストリームを使用する場合のみ呼び出してください。
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
		 * 指定されたバイナリデータを Block として送信します。
		 */
		def sendEOF():MessageSink = {
			block(Block.eof(Pipe.this.id))
			this
		}

		// ============================================================================================
		// ブロックの送信
		// ============================================================================================
		/**
		 * 指定されたバイナリデータを Block として送信します。
		 */
		def send(buffer:Array[Byte]):MessageSink = {
			block(buffer, 0, buffer.length)
			this
		}

		// ============================================================================================
		// ブロックの送信
		// ============================================================================================
		/**
		 * 指定されたバイナリデータを Block として送信します。
		 */
		def send(buffer:Array[Byte], offset:Int, length:Int):MessageSink = {
			Pipe.this.block(buffer, offset, length)
			this
		}

		// ============================================================================================
		// ブロックの送信
		// ============================================================================================
		/**
		 * 指定されたバイナリデータを Block として送信します。
		 */
		def << (buffer:Array[Byte]):MessageSink = {
			send(buffer)
			this
		}
	}
}

object Pipe {
	private[Pipe] val logger = LoggerFactory.getLogger(classOf[Pipe])

	private[this] val pipes = new ThreadLocal[Pipe]()

	def apply():Option[Pipe] = Option(pipes.get())

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
		def apply(block:Block):Unit = synchronized {
			sequence(block)
			if(block.isEOF){
				finish()
			}
		}
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
 * このパイプ上で受信したブロックをストリームとして参照するためのクラス。
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
		//	None
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
 * このパイプからブロックとしてデータを送信するための出力ストリームです。出力されたバイナリは内部でバッファリング
 * され、フラグメント化されたブロックとして送信されます。
 * このパイプを使用した出力ストリームクラス。出力内容を Block にカプセル化して非同期セッションへ出力する。
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