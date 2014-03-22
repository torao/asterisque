/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import org.specs2.Specification
import scala.concurrent.{Await, Promise, Future}
import com.kazzla.asterisk.codec.MsgPackCodec
import scala.concurrent.ExecutionContext.Implicits._
import java.util.{TimerTask, Timer}
import scala.concurrent.duration.Duration

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// ServiceSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class ServiceSpec extends Specification { def is = s2"""
Service should:
support asynchronous function call via interface. $e0
support low-level function call without interface. $e1
support low-level function call to a method that implemented by interface. $e2
support asynchronous funtion call to a method that implemented by low-level operation. $e3
"""


	def e0 = {
		val service = new TestService()
		trx(new Service(global){ }, service){ (client, server) =>
			val logger = client.bind(classOf[Test])
			val text = randomString
			Await.result(logger.reverse(text), Duration.Inf) === _reverse(text)
		}
	}

	def e1 = {
		val service = new TestService()
		trx(new Service(global){ }, service){ (client, server) =>
			val text = randomString
			val promise = Promise[String]()
			client.open(20, text).onSuccess{ case result =>
				promise.success(result.asInstanceOf[String])
			}
			Await.result(promise.future, Duration.Inf) ===  _reverse(text)
		}
	}

	def e2 = {
		val service = new TestService()
		trx(new Service(global){ }, service){ (client, server) =>
			val text = randomString
			val promise = Promise[String]()
			client.open(10, text).onSuccess{ case result => promise.success(result.asInstanceOf[String]) }
			Await.result(promise.future, Duration.Inf) ===  _reverse(text)
		}
	}

	def e3 = {
		val service = new TestService()
		trx(new Service(global){ }, service){ (client, server) =>
			val text = randomString
			val future = client.bind(classOf[AltTest]).rawReverse(text)
			Await.result(future, Duration.Inf) ===  _reverse(text)
		}
	}

	val timer = new Timer("async response timer", true)

	private[this] def trx[T](c:Service, s:Service)(f:(Session,Session)=>T):T = {
		val client = Node("client").codec(MsgPackCodec).serve(c).build()
		val server = Node("server").codec(MsgPackCodec).serve(s).build()
		val (w0, w1) = Wire.newPipe()
		val s0 = client.bind(w0)
		val s1 = server.bind(w1)
		val result = f(s0, s1)
		client.shutdown()
		server.shutdown()
		result
	}

	trait Test {
		@Export(10)
		def reverse(text:String):Future[String]
	}

	trait AltTest {
		@Export(20)
		def rawReverse(text:String):Future[String]
	}

	class TestService extends Service(global) with Test {
		def reverse(text:String) = {
			val promise = Promise[String]()
			timer.schedule(new TimerTask {
				def run(): Unit = {
					promise.success(_reverse(text))
				}
			}, 500)
			promise.future
		}
		20 accept { args => Future(_reverse(args.head.toString)) }
	}

	private[this] def randomString = {
		val sample = ('0' to '9').toList ::: ('a' to 'z').toList ::: ('A' to 'Z').toList
		new String(util.Random.shuffle(sample).toArray)
	}

	private[this] def _reverse(text:String):String = new String(text.toCharArray.reverse)

}
