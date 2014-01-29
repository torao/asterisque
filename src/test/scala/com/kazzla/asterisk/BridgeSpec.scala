/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.net.InetSocketAddress
import scala.concurrent._
import scala.concurrent.duration._
import org.specs2.Specification
import org.specs2.execute.Result
import com.kazzla.asterisk.codec.MsgPackCodec
import javax.net.ssl.SSLContext

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// BridgeSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
abstract class BridgeSpec extends Specification { def is = s2"""
Bridge should:
"""

	def bridge:Bridge
}

abstract class BridgeWireSpec(client:Option[SSLContext], server:Option[SSLContext]) extends WireSpec {
	def bridge:Bridge

	private[this] val waitLimit = Duration.apply(10, SECONDS)

	def wires(fx:(Wire,Wire)=>Result) = synchronized {
		try {
			val b = bridge
			val p2 = Promise[Wire]()
			val s = b.listen(MsgPackCodec, new InetSocketAddress("localhost", 39888), server){ w => p2.success(w) }
			using(Await.result(s, waitLimit)){ _ =>
				val f = b.connect(MsgPackCodec, new InetSocketAddress("localhost", 39888), client)
				using(Await.result(f, waitLimit)){ w1 =>
					using(Await.result(p2.future, waitLimit)){ w2 =>
						fx(w1, w2)
					}
				}
			}
		} catch {
			case ex:Throwable =>
				ex.printStackTrace()
				failure
		}
	}

	override def secureWire(fx:(Wire)=>Result) = synchronized {
		wires{ (w1, w2) => fx(w1) and fx(w2) }
	}

}
