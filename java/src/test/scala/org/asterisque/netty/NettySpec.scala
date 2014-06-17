/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.netty

import java.net.InetSocketAddress

import org.specs2.Specification
import org.asterisque._
import java.util.concurrent.{TimeUnit, Executors}
import org.asterisque.cluster.Repository
import org.asterisque.codec.MessagePackCodec

import scala.concurrent.{Promise, Await}
import scala.concurrent.duration.Duration

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// NettySpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class NettySpec extends Specification{ def is = s2"""
Netty should:
sample bidirectional server and client interaction. $e0
"""
	val waitTime = Duration(3, TimeUnit.SECONDS)

	def e0 = {
		trait Echo {
			@Export(100)
			def echo(text:String):String
		}
		object EchoService extends Service {
			def echo(text:String) = text
		}
		trait Reverse {
			@Export(100)
			def reverse(text:String):String
		}
		object ReverseService extends Service {
			def reverse(text:String) = new StringBuilder(text).reverse.toString
		}

		val p0 = Promise[String]()
		val p1 = Promise[String]()

		val repository = Repository.OnMemory
		val exec = Executors.newCachedThreadPool(Asterisque.newThreadFactory("test"))
		val bridge = new Netty()

		val address = new InetSocketAddress(9433)
		val echo = new LocalNode("echo", exec, EchoService, repository)
		val future = echo.listen(address, new Options()
			.set(Options.KEY_BRIDGE, bridge)
			.set(Options.KEY_CODEC, MessagePackCodec.getInstance())){ session =>
			val r = session.bind(classOf[Reverse])
			p0.success(r.reverse("ABCDEFG"))
		}
		locally {
			Await.ready(future, waitTime)
		}

		val reverse = new LocalNode("reverse", exec, ReverseService, repository)
		val session = reverse.connect(address, new Options()
			.set(Options.KEY_BRIDGE, bridge)
			.set(Options.KEY_CODEC, MessagePackCodec.getInstance()))
		locally {
			val e = session.bind(classOf[Echo])
			p1.success(e.echo("XYZ"))
		}

		(Await.result(p0.future, waitTime) === "GFEDCBA") and (Await.result(p1.future, waitTime) === "XYZ")
	}
}
