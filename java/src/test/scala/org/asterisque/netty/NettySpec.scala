/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.netty

import java.net.InetSocketAddress
import java.util.UUID

import org.slf4j.LoggerFactory
import org.specs2.Specification
import org.asterisque._
import java.util.concurrent.{CompletableFuture, TimeUnit, Executors}
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
	val logger = LoggerFactory.getLogger(classOf[NettySpec])
	val waitTime = Duration(3, TimeUnit.SECONDS)

	def e0 = {

		trait Echo {
			@Export(100)
			def echo(text:String):CompletableFuture[String]
		}
		object EchoService extends Service {
			def echo(text:String) = {
				logger.info(s"echo($text)")
				val c = new CompletableFuture[String]()
				c.complete(text)
				c
			}
		}

		val repository = Repository.OnMemory
		val exec = Executors.newCachedThreadPool(Asterisque.newThreadFactory("test"))
		val bridge = new Netty()

		val address = new InetSocketAddress(9433)
		val server = new LocalNode(UUID.randomUUID(), "echo", exec, EchoService, repository)
		val startup = server.listen(address, new Options()
			.set(Options.KEY_BRIDGE, bridge)
			.set(Options.KEY_CODEC, MessagePackCodec.getInstance())){ session => None }
		Await.ready(startup, waitTime)

		val client = new LocalNode(UUID.randomUUID(), "reverse", exec, new Service {}, repository)
		val future2 = client.connect(address, new Options()
			.set(Options.KEY_BRIDGE, bridge)
			.set(Options.KEY_CODEC, MessagePackCodec.getInstance()))
		val future = locally {
			val session = Await.result(future2, waitTime)
			val e = session.bind(classOf[Echo])
			e.echo("XYZ")
		}

		Await.result(future, waitTime) === "XYZ"
	}

	def e1 = {

		trait Echo {
			@Export(100)
			def echo(text:String):String
		}
		object EchoService extends Service {
			def echo(text:String) = text
		}
		trait Reverse {
			@Export(100)
			def reverse(text:String):CompletableFuture[String]
		}
		object ReverseService extends Service {
			def reverse(text:String) = new StringBuilder(text).reverse.toString()
		}

		val p0 = Promise[String]()
		val p1 = Promise[String]()

		val repository = Repository.OnMemory
		val exec = Executors.newCachedThreadPool(Asterisque.newThreadFactory("test"))
		val bridge = new Netty()

		val address = new InetSocketAddress(9433)
		val echo = new LocalNode(UUID.randomUUID(), "echo", exec, EchoService, repository)
		val future = echo.listen(address, new Options()
			.set(Options.KEY_BRIDGE, bridge)
			.set(Options.KEY_CODEC, MessagePackCodec.getInstance())){ session =>
			val r = session.bind(classOf[Reverse])
			p0.completeWith(r.reverse("ABCDEFG"))
		}
		Await.ready(future, waitTime)

		val reverse = new LocalNode(UUID.randomUUID(), "reverse", exec, ReverseService, repository)
		val future2 = reverse.connect(address, new Options()
			.set(Options.KEY_BRIDGE, bridge)
			.set(Options.KEY_CODEC, MessagePackCodec.getInstance()))
		locally {
			val session = Await.result(future2, waitTime)
			val e = session.bind(classOf[Echo])
			p1.success(e.echo("XYZ"))
		}

		(Await.result(p0.future, waitTime) === "GFEDCBA") and (Await.result(p1.future, waitTime) === "XYZ")
	}
}
