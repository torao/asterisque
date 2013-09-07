/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.io.{IOException, OutputStream, InputStream}
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
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
class Pipe private[asterisk](val id:Short, session:Session) {
	private[asterisk] val receiveQueue = new LinkedBlockingQueue[Block]()

	@volatile
	private[asterisk] var closed = false

	lazy val in:InputStream = new IS()
	lazy val out:OutputStream = new OS()

	private[asterisk] val promise = Promise[Close[_]]()
	val future = promise.future

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
		receiveQueue.put(Block.eof(id))
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
			if(isClosed){
				None
			} else if(processing.isDefined && processing.get.hasRemaining){
				processing
			} else if(receiveQueue.isEmpty && closed){
				None
			} else receiveQueue.take() match {
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

	private[asterisk] val UNIQUE_MASK:Short = (1 << 15).toShort

	private[asterisk] val pipes = new ThreadLocal[Pipe]()

	// ==============================================================================================
	// パイプの参照
	// ==============================================================================================
	/**
	 * 現在のスレッドを実行しているパイプを参照します。
	 * @return 現在のセッション
	 */
	def apply():Option[Pipe] = Option(pipes.get())

}