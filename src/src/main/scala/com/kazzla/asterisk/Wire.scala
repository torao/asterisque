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

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Wire
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * Session より先に生成します。
 * @author Takami Torao
 */
trait Wire {

	@volatile
	private[this] var active = false
	private[this] val closed = new AtomicBoolean(false)

	private[this] val buffer = new mutable.ArrayBuffer[Message]()

	def isServer:Boolean

	def isClosed:Boolean = closed.get()

	/**
	 * サブクラスが下層のネットワーク実装からメッセージを受信したときに呼び出します。
	 */
	protected def receive(msg:Message):Unit = buffer.synchronized {
		if(active){
			onReceive(msg)
		} else {
			buffer.append(msg)
		}
	}

	/**
	 * メッセージの送信を開始します。
	 */
	def start():Unit = buffer.synchronized {
		buffer.foreach{ msg => onReceive(msg) }
		buffer.clear()
		active = true
	}

	/**
	 * メッセージの送信を停止します。
	 * 停止中に受信したメッセージは内部のバッファに保持され次回開始したときに通知されます。
	 */
	def stop():Unit = buffer.synchronized {
		active = false
	}

	val onReceive = new EventHandlers[Message]()
	val onClosed = new EventHandlers[Wire]()
	def peerName:String = ""
	def tls:Future[Option[SSLSession]] = Promise.successful(None).future
	def send(m:Message):Unit
	def close():Unit = if(closed.compareAndSet(false, true)){
		onClosed(this)
	}
}

object Wire {

	def newPipe():(Wire,Wire) = {
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