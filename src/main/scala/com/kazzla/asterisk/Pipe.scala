/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.io.{IOException, OutputStream, InputStream}
import java.nio.ByteBuffer
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}
import scala.concurrent.Promise
import org.slf4j.LoggerFactory

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
class Pipe private[asterisk](val id:Short, val function:Short, session:Session) {

	/**
	 * 受信した [[com.kazzla.asterisk.Block]] インスタンスを保持するためのキュー。
	 */
	private[this] var receiveQueue:Option[BlockingQueue[Block]] = None

	@volatile
	private[asterisk] var closed = false

	/**
	 * このパイプが受信したデータをストリームとして参照するための入力ストリームです。ブロックとして到着したデータは
	 * 内部でバッファリングされ連続したストリームとして参照されます。
	 * [[com.kazzla.asterisk.Session.open()]] を使用して作成されたパイプのブロックはブロックのハンドラに渡され
	 * るためこの入力ストリーム経由で取り出すことはできません。
	 */
	lazy val in:InputStream = new IS()

	/**
	 * このパイプからブロックとしてデータを送信するための出力ストリームです。出力されたバイナリは内部でバッファリング
	 * され、フラグメント化されたブロックとして送信されます。
	 */
	lazy val out:OutputStream = new OS()

	private[asterisk] val promise = Promise[Close[_]]()

	/**
	 * このパイプが相手側からクローズされた時に [[com.kazzla.asterisk.Close]] を参照するための Future です。
	 */
	val future = promise.future

	private[this] var _blockReceiver:Option[(Block)=>Unit] = None

	def blockReceiver_=(f:Option[(Block)=>Unit]):Unit = {
		_blockReceiver = f
		receiveQueue = if(f.isDefined){
			Some(new LinkedBlockingQueue[Block]())
		} else {
			None
		}
	}

	private[asterisk] def receive(block:Block):Unit = _blockReceiver match {
		case Some(r) => r(block)
		case None => receiveQueue.foreach{ _.put(block) }
	}

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
	// パイプのクローズ
	// ==============================================================================================
	/**
	 * 指定した result 付きで Close を送信しこのパイプを閉じます。
	 * @param result Close に付加する結果
	 */
	def close[T](result:T):Unit = {
		session.post(Close[T](id, result, null))
		session.destroy(id)
		closed = true
	}

	// ==============================================================================================
	// パイプのクローズ
	// ==============================================================================================
	/**
	 * 指定した例外付きで Close を送信しパイプを閉じます。
	 * @param ex Close に付加する例外
	 */
	def close(ex:Throwable):Unit = {
		session.post(Close[AnyRef](id, null, ex.toString))
		session.destroy(id)
		closed = true
	}

	// ==============================================================================================
	// パイプのクローズ
	// ==============================================================================================
	/**
	 * 相手側から受信した Close によってこのパイプを閉じます。
	 */
	private[asterisk] def close(close:Close[_]):Unit = {
		receiveQueue.foreach{ _.put(Block.eof(id)) }
		session.destroy(id)
		closed = true
		promise.success(close)
	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// IS
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * このパイプ上で受信したブロックをストリームとして参照するためのクラス。
	 */
	private[this] class IS extends InputStream {
		if(receiveQueue.isEmpty){
			throw new IOException("pipe with open(func,param)(receiver) cannot open input stream")
		}
		private[this] var processing:Option[ByteBuffer] = None
		private[this] var isClosed = false
		def read():Int = processingBuffer match {
			case None => -1
			case Some(buffer) => buffer.get() & 0xFF
		}
		override def read(b:Array[Byte]):Int = read(b, 0, b.length)
		override def read(b:Array[Byte], offset:Int, length:Int):Int = processingBuffer match {
			case None => -1
			case Some(buffer) =>
				val len = math.min(length, buffer.remaining())
				buffer.get(b, offset, len)
				len
		}
		override def close():Unit = {
			// TODO 読み出しクローズしたキューにそれ以上ブロックを入れない処理
		}
		private[this] def processingBuffer:Option[ByteBuffer] = {
			if(closed || isClosed){
				None
			} else if(processing.isDefined && processing.get.hasRemaining){
				processing
			} else if(receiveQueue.isEmpty && closed){
				None
			} else receiveQueue.get.take() match {
				case Block(pipeId, binary, offset, length) =>
					if(length <= 0){
						isClosed = true
						None
					} else {
						processing = Some(ByteBuffer.wrap(binary, offset, length))
						processing
					}
			}
		}
	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// OS
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * このパイプを使用した出力ストリームクラス。出力内容を Block にカプセル化して非同期セッションへ出力する。
	 */
	private[this] class OS extends OutputStream {
		private[this] val buffer = ByteBuffer.allocate(4 * 1024)
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
			if(closed || osClosed){
				throw new IOException(f"unable to write to closed pipe or stream: 0x$id%02X")
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
				block(b)
				buffer.clear()
			}
		}
		override def close():Unit = {
			flush()
			block(Array[Byte]())
			osClosed = true
		}
	}

}

object Pipe {
	private[Pipe] val logger = LoggerFactory.getLogger(classOf[Pipe])

	/**
	 * [[com.kazzla.asterisk.Wire.isServer]] が true の通信端点側で新しいパイプ ID を発行するときに立てる
	 * ビット。
	 */
	private[asterisk] val UNIQUE_MASK:Short = (1 << 15).toShort

	/**
	 * メソッド呼び出し中にパイプを保持するためのスレッドローカル。
	 */
	private[asterisk] val pipes = new ThreadLocal[Pipe]()

	// ==============================================================================================
	// パイプの参照
	// ==============================================================================================
	/**
	 * 現在のスレッドを実行しているパイプを参照します。
	 * @return 現在のパイプ
	 */
	def apply():Option[Pipe] = Option(pipes.get())

}