/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import org.specs2.Specification
import com.kazzla.asterisk.codec.MsgPackCodec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import org.asterisque.Export

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// SessionSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class SessionSpec extends Specification { def is = s2"""
Session should:
bw able to call function with no parameters: $s01
callback close event when peer wire is closed: $s02
"""

	def s01 = {
		trait T0 {
			@Export(0) def hoge():Future[String]
		}
		class C0 extends Service(global) with T0 {
			def hoge() = Future("hoge")
		}
		val n0 = Node("node0").codec(MsgPackCodec).serve(new C0).build()
		val n1 = Node("node1").codec(MsgPackCodec).build()
		val (p0, p1) = Wire.newPipe()
		n0.bind(p0)
		val s1 = n1.bind(p1)
		Await.result(s1.bind(classOf[T0]).hoge(), Duration.Inf) === "hoge"
	}

	def s02 = {
		val n0 = Node("node0").codec(MsgPackCodec).build()
		val n1 = Node("node1").codec(MsgPackCodec).build()
		val (p0, p1) = Wire.newPipe()
		val s0 = n0.bind(p0)
		val s1 = n1.bind(p1)
		var closed = false
		s1.onClosed ++ { _ => closed = true }
		p0.close()
		p1.isClosed and closed
	}
}
