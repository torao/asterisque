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
class Pipe private[asterisk](val id:Short, val function:Short, val session:Session) {

	private[this] val closed = new AtomicBoolean(false)

	private[asterisk] val onBlock = new EventHandlers[Block]()
	private[asterisk] val onSuccess = new EventHandlers[Any]()
	private[asterisk] val onFailure = new EventHandlers[Throwable]()

	// ==============================================================================================
	// ブロックの送信
	// ==============================================================================================
	/**
	 * 指定されたバイナリデータを Block として送信します。
	 */
	def block(buffer:Array[Byte]):Unit = block(buffer, 0, buffer.length)

	// ==============================================================================================
	// ブロックの送信
	// ==============================================================================================
	/**
	 * 指定されたバイナリデータを Block として送信します。
	 */
	def block(buffer:Array[Byte], offset:Int, length:Int):Unit = {
		session.post(Block(id, buffer, offset, length))
	}

	// ==============================================================================================
	// ブロックの受信
	// ==============================================================================================
	/**
	 * 指定された Block を受信したときに呼び出されます。
	 */
	private[asterisk] def block(block:Block):Unit = onBlock(block)

	// ==============================================================================================
	// パイプのクローズ
	// ==============================================================================================
	/**
	 * 指定した result 付きで Close を送信しこのパイプを閉じます。
	 * @param result Close に付加する結果
	 */
	def close[T](result:T):Unit = if(closed.compareAndSet(false, true)){
		session.post(Close[T](id, result, null))
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
			onFailure(new RemoteException(close.errorMessage))
		} else {
			onSuccess(close.result)
		}
		session.destroy(id)
		Pipe.logger.trace(s"pipe $id is closed by peer: $close")
	} else {
		Pipe.logger.debug(s"pipe $id already closed: $close")
	}
}

object Pipe {
	private[Pipe] val logger = LoggerFactory.getLogger(classOf[Pipe])

	private[this] val pipes = new ThreadLocal[Pipe]()

	def apply():Option[Pipe] = Option(pipes.get())

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

	class Builder private[asterisk](session:Session, function:Short) {
		private[this] val _onBlock = new EventHandlers[Block]()
		private[this] val _onSuccess = new EventHandlers[Any]()
		private[this] val _onFailure = new EventHandlers[Throwable]()

		def onBlock(f:(Block)=>Unit):Builder = {
			_onBlock ++ f
			this
		}
		def onSuccess(f:(Any)=>Unit):Builder = {
			_onSuccess ++ f
			this
		}
		def onFailure(f:(Throwable)=>Unit):Builder = {
			_onFailure ++ f
			this
		}

		def call(params:Any*):Pipe = {
			val pipe = session.create(function, params)
			pipe.onBlock +++ _onBlock
			pipe.onSuccess +++ _onSuccess
			pipe.onFailure +++ _onFailure
			session.post(Open(pipe.id, function, params))
			pipe
		}
	}

}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// PipeInputStream
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * このパイプ上で受信したブロックをストリームとして参照するためのクラス。
 */
class PipeInputStream extends InputStream with ((Block) => Unit) {

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
class PipeOutputStream(pipe:Pipe, bufferSize:Int) extends OutputStream {
	def this(pipe:Pipe) = this(pipe, 4 * 1024)
	private[this] val buffer = ByteBuffer.allocate(bufferSize)
	private[this] var osClosed = false
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
		if(buffer.position() + len > buffer.capacity()){
			flush()
		}
	}
	override def flush():Unit = {
		if(buffer.position() > 0){
			buffer.flip()
			val b = new Array[Byte](buffer.remaining())
			buffer.get(b, 0, b.length)
			pipe.block(b)
			buffer.clear()
		}
	}
	override def close():Unit = {
		flush()
		pipe.block(Array[Byte]())
		osClosed = true
	}
}
