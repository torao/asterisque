/*
 * Copyright (c) 2013 ssskoiroha.org.
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

	private[this] val closed = new AtomicBoolean(false)

	private[asterisk] val onBlock = new EventHandlers[Block]()

	private[this] val promise = Promise[Any]()
	lazy val future = promise.future

	lazy val blocks = new Blocks()

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
	private[this] def block(block:Block):Unit = session.post(block)

	// ==============================================================================================
	// パイプのクローズ
	// ==============================================================================================
	/**
	 * 指定した result 付きで Close を送信しこのパイプを閉じます。
	 * @param result Close に付加する結果
	 */
	def close[T](result:T):Unit = if(closed.compareAndSet(false, true)){
		session.post(Close[T](id, result, null))
		promise.success(result)
		session.destroy(id)
		Pipe.logger.trace(s"pipe $id is closed with success: $result")
	} else {
		Pipe.logger.debug(s"pipe $id already closed: $result")
	}

	// ==============================================================================================
	// パイプのクローズ
	// ==============================================================================================
	/**
	 * 指定した例外付きで Close を送信しパイプを閉じます。
	 * @param ex Close に付加する例外
	 */
	def close(ex:Throwable):Unit = if(closed.compareAndSet(false, true)){
		session.post(Close[AnyRef](id, null, ex.toString))
		promise.failure(ex)
		session.destroy(id)
		Pipe.logger.trace(s"pipe $id is closed with failure: $ex")
	} else {
		Pipe.logger.debug(s"pipe $id already closed: $ex")
	}

	// ==============================================================================================
	// パイプのクローズ
	// ==============================================================================================
	/**
	 * 相手側から受信した Close によってこのパイプを閉じます。
	 */
	private[asterisk] def close(close:Close[_]):Unit = if(closed.compareAndSet(false, true)){
		if(close.errorMessage != null){
			val ex = new RemoteException(close.errorMessage)
			promise.failure(ex)
		} else {
			promise.success(close.result)
		}
		session.destroy(id)
		Pipe.logger.trace(s"pipe $id is closed by peer: $close")
	} else {
		Pipe.logger.debug(s"pipe $id already closed: $close")
	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// Block
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * パイプに対してブロック送受信 (ストリーム) 操作をおこなうためのクラス。
	 * メッセージ配信スレッド内でハンドラを設定する必要があります。
	 */
	class Blocks private[Pipe]() extends Source[Block] {

		/**
		 * このパイプにハンドラが設定される前に受信したブロックを保持するためのキュー。
		 */
		// private[this] val premature = new LinkedBlockingQueue[Block]()

		// @volatile
		// private[this] var handler:Option[(Block)=>Unit] = None
		private[this] var _useStream = false

		/**
		 * このパイプに対するブロック出力を `OutputStream` として行います。ストリームに対する出力データがバッファ
		 * サイズに達するか `flush()` が実行されると [[Block]] として送信されます。
		 * このストリームへの出力操作は I/O ブロックを受けません。
		 */
		lazy val out:PipeOutputStream = new PipeOutputStream(Pipe.this)

		/**
		 * このパイプが受信したブロックを `InputStream` として参照します。
		 * @@[[com.kazzla.asterisk.Export]](stream=true) 宣言されているメソッドのパイプのみ使用することが
		 * 出来ます。
		 */
		lazy val in:PipeInputStream = if(_useStream){
			val is = new PipeInputStream()
			foreach(is)
			is
		} else {
			throw new IOException(s"pipe $id is not declared as stream=true")
		}

		private[this] def receive(block:Block):Unit = synchronized{
			if(block.isEOF){
				finish()
			} else {
				sequence(block)
			}
			// handler match {
			// 	case Some(h) => h(block)
			// 	case None => premature.put(block)
			// }
		}

		/**
		 * 受信したブロックを受け取るハンドラを設定します。
		 * @@[[com.kazzla.asterisk.Export]](stream=true) 宣言されているメソッドのパイプのみ使用することが
		 * 出来ます。
		 */
		/*
		def receive(f:(Block)=>Unit):Pipe = if(! _useStream){
			throw new IOException(s"block streaming is not available on function declared as stream=false")
		} else synchronized {
			handler match {
				case Some(_) =>
					throw new IOException(s"other handler is already handling blocks")
				case None =>
					while(! premature.isEmpty){
						f(premature.take())
					}
					handler = Some(f)
			}
			Pipe.this
		}

		def >> (f:(Block)=>Unit):Unit = receive(f)
		*/

		// ============================================================================================
		// ブロックの送信
		// ============================================================================================
		/**
		 * 指定されたバイナリデータを Block として送信します。
		 */
		def sendEOF():Unit = block(Block.eof(Pipe.this.id))

		// ============================================================================================
		// ブロックの送信
		// ============================================================================================
		/**
		 * 指定されたバイナリデータを Block として送信します。
		 */
		def send(buffer:Array[Byte]):Unit = block(buffer, 0, buffer.length)

		// ============================================================================================
		// ブロックの送信
		// ============================================================================================
		/**
		 * 指定されたバイナリデータを Block として送信します。
		 */
		def send(buffer:Array[Byte], offset:Int, length:Int):Unit = Pipe.this.block(buffer, offset, length)

		// ============================================================================================
		// ブロックの送信
		// ============================================================================================
		/**
		 * 指定されたバイナリデータを Block として送信します。
		 */
		def << (buffer:Array[Byte]):Unit = send(buffer)

		private[asterisk] def useStream():Unit = {
			onBlock ++ receive
			_useStream = true
		}
	}
}

object Pipe {
	private[Pipe] val logger = LoggerFactory.getLogger(classOf[Pipe])

	private[this] val pipes = new ThreadLocal[Pipe]()

	def apply():Option[Pipe] = Option(pipes.get())

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

}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// PipeInputStream
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * このパイプ上で受信したブロックをストリームとして参照するためのクラス。
 */
class PipeInputStream private[asterisk]() extends InputStream with ((Block) => Unit) {

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

	def apply(block:Block):Unit = if(! closed){
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

	override def close():Unit = closed = true

	private[this] def processingBuffer:Option[ByteBuffer] = {
		if(eof){
			None
		} else if(processing.isDefined && processing.get.hasRemaining){
			processing
		} else if(receiveQueue.isEmpty){
			None
		} else {
			val Block(_, binary, offset, length) = receiveQueue.take()
			if(length <= 0){
				eof = true   // EOF 検出
				None
			} else {
				processing = Some(ByteBuffer.wrap(binary, offset, length))
				processing
			}
		}
	}
	private[this] def ensureOpen():Unit = if(closed){
		throw new IOException("stream closed")
	}
}


// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// PipeOutputStream
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * このパイプからブロックとしてデータを送信するための出力ストリームです。出力されたバイナリは内部でバッファリング
 * され、フラグメント化されたブロックとして送信されます。
 * このパイプを使用した出力ストリームクラス。出力内容を Block にカプセル化して非同期セッションへ出力する。
 */
class PipeOutputStream private[asterisk](pipe:Pipe, bufferSize:Int) extends OutputStream {
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
			throw new IOException(s"unable to write to closed pipe or stream: ${pipe.id}")
		}
		// TODO バッファサイズより大きなデータが書き込めない?
		if(buffer.position() + len > buffer.capacity()){
			flush()
		}
	}
	override def flush():Unit = {
		if(buffer.position() > 0){
			buffer.flip()
			while(buffer.hasRemaining){
				val len = math.min(buffer.remaining(), Block.MaxPayloadSize)
				val b = new Array[Byte](len)
				buffer.get(b, 0, b.length)
				pipe.blocks.send(b)
			}
			buffer.clear()
		}
	}
	override def close():Unit = {
		flush()
		pipe.blocks.send(Array[Byte]())
		osClosed = true
	}
}
