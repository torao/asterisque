/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.net.InetSocketAddress
import scala.concurrent._
import scala.concurrent.duration.Duration
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

	def wires(fx:(Wire,Wire)=>Result) = synchronized {
		val b = bridge
		val p2 = Promise[Wire]()
		val s = b.listen(MsgPackCodec, new InetSocketAddress("localhost", 39888), server){ w => p2.success(w) }
		using(Await.result(s, Duration.Inf)){ _ =>
			val f = b.connect(MsgPackCodec, new InetSocketAddress("localhost", 39888), client)
			using(Await.result(f, Duration.Inf)){ w1 =>
				using(Await.result(p2.future, Duration.Inf)){ w2 =>
					fx(w1, w2)
				}
			}
		}
	}

	override def secureWire(fx:(Wire)=>Result) = synchronized {
		wires{ (w1, w2) => fx(w1) and fx(w2) }
	}

}
