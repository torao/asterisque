/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import scala.concurrent.{Promise, Future}
import javax.net.ssl.SSLSession

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Wire
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * Session より先に生成します。
 * @author Takami Torao
 */
trait Wire {
	val onReceive = new EventHandlers[Message]()
	val onClosed = new EventHandlers[Wire]()
	def peerName:String
	def tls:Option[SSLSession] = None
	def open():Future[Wire]
	def send(m:Message):Unit
	def close():Unit = onClosed(this)
}

object Wire {

	def newPipe():(Wire,Wire) = {
		import scala.language.reflectiveCalls
		val w1 = new Wire {
			var f:(Message)=>Unit = null
			def send(m:Message) { f(m) }
			def open() { Promise.successful(this).future }
			def close() {}
		}
		lazy val w2 = new Wire {
			def send(m:Message) { w1.onReceive(m) }
			def open() { Promise.successful(this).future }
			def close() {}
		}
		w1.f = { m => w2.onReceive(m) }
		(w1, w2)
	}
}