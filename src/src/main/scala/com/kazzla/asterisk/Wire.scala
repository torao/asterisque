/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import scala.concurrent.{Future, Promise}
import scala.collection._
import javax.net.ssl.SSLSession
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Wire
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * Session より先に生成します。
 * @author Takami Torao
 */
trait Wire {
	import Wire._

	/**
	 * この `Wire` がメッセージ受信通知を開始しているかどうかを表すフラグ。
	 */
	private[this] val active = new AtomicBoolean(false)

	/**
	 * この `Wire` が既にクローズされているかを表すフラグ。
	 */
	private[this] val closed = new AtomicBoolean(false)

	/**
	 * この `Wire` が開始されていない状態でサブクラスからメッセージ到着の通知があった場合に、内部的にメッセージを
	 * 保持するためのバッファです。このバッファに保持されたメッセージは `start()` の呼び出しですべてのイベント
	 * ハンドラへ通知されます。
	 */
	private[this] val buffer = new mutable.ArrayBuffer[Message]()

	/**
	 * この `Wire` がメッセージを受信したときに通知を行うイベントハンドラです。メッセージの受信通知は `start()` が
	 * 呼び出されてから `stop()` が呼び出されるまのオンライン状態で行われます。オフライン中に受信したメッセージは
	 * `Wire` 内部にバッファリングされ次回 `start()` が呼び出されたときに通知されます。
	 */
	val onReceive = new EventHandlers[Message]()

	/**
	 * この `Wire` がクローズされたときに通知を行うイベントハンドラです。
	 */
	val onClosed = new EventHandlers[Wire]()

	/**
	 * この `Wire` の表すエンドポイントがサーバ側の場合に true を返します。
	 * このフラグは通信相手との合意なしにユニークな ID を発行できるようにするために使用されます。例えば新しいパイプを
	 * 開くときの ID の最上位ビットを立てるかどうかに使用することで相手との合意なしにユニークなパイプ ID 発行を行って
	 * います。この取り決めから通信の双方でこの値が異なっている必要があります。
	 * @return サーバ側の場合 true
	 */
	def isServer:Boolean

	/**
	 * @return この `Wire` がクローズされているときに true
	 */
	def isClosed:Boolean = closed.get()

	/**
	 * @return この `Wire` がメッセージ受信通知を行っているとき true
	 */
	def isActive:Boolean = active.get()

	/**
	 * @return 通信相手を人が識別するための文字列
	 */
	def peerName:String = ""

	/**
	 * @return 認証された通信相手の SSL セッション
	 */
	def tls:Future[Option[SSLSession]] = Promise.successful(None).future

	// ==============================================================================================
	// メッセージの送信
	// ==============================================================================================
	/**
	 * 指定されたメッセージを送信します。
	 * @param msg 送信するメッセージ
	 */
	def send(msg:Message):Unit

	// ==============================================================================================
	// メッセージの受信
	// ==============================================================================================
	/**
	 * 下層のネットワーク実装からメッセージを受信したときに呼び出します。
	 * @param msg 受信したメッセージ
	 */
	protected def receive(msg:Message):Unit = if(active.get()) {
		onReceive(msg)
	} else buffer.synchronized {
		buffer.append(msg)
		logger.debug(s"message buffered on closed wire: $msg")
	}

	// ==============================================================================================
	// メッセージ受信通知の開始
	// ==============================================================================================
	/**
	 * この `Wire` 上で受信したメッセージの `onReceive` イベントハンドラへの配信を開始します。
	 * 下層の非同期 I/O 実装が `Wire` の構築と同時にメッセージ受信を開始する場合に、イベントハンドラが正しく設定さ
	 * れるまで受信したメッセージをバッファリングする事を目的としています。
	 */
	def start():Unit = if(active.compareAndSet(false, true)){
		buffer.foreach{ msg => onReceive(msg) }
		buffer.clear()
	}

	// ==============================================================================================
	// メッセージ受信通知の停止
	// ==============================================================================================
	/**
	 * この `Wire` 上でのメッセージ配信を停止します。
	 * 停止中に受信したメッセージは内部のバッファに保持され次回開始したときに通知されます。
	 */
	def stop():Unit = {
		active.compareAndSet(true, false)
	}

	// ==============================================================================================
	// クローズ
	// ==============================================================================================
	/**
	 * この `Wire` をクローズします。インスタンスに対して最初に呼び出されたタイミングで `onClosed` イベント
	 * ハンドラへのコールバックが行われます。このメソッドを複数回呼び出しても二度目以降は無視されます。
	 */
	def close():Unit = if(closed.compareAndSet(false, true)){
		onClosed(this)
	}
}

object Wire {
	private[Wire] val logger = LoggerFactory.getLogger(classOf[Wire])

	// ==============================================================================================
	// パイプの作成
	// ==============================================================================================
	/**
	 * [[com.kazzla.asterisk.codec.Codec]] を伴わないでメッセージをやり取りするパイプ型 Wire を構築します。
	 * @return パイプ Wire の双方のタプル
	 */
	def newPipe():(Wire, Wire) = {
		import scala.language.reflectiveCalls
		val w1 = new Wire {
			var f:(Message)=>Unit = null
			val isServer = false
			def send(m:Message) { f(m) }
		}
		lazy val w2 = new Wire {
			val isServer = ! w1.isServer
			def send(m:Message) { w1.onReceive(m) }
		}
		w1.f = { m => w2.onReceive(m) }
		(w1, w2)
	}

}