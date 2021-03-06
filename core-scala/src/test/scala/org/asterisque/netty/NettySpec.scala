/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.netty

import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import org.slf4j.LoggerFactory
import org.specs2.Specification
import org.asterisque._
import java.util.concurrent.{CompletableFuture, TimeUnit, Executors}
import org.asterisque.cluster.Repository
import org.asterisque.codec.MessagePackCodec
import org.specs2.matcher.MatchResult

import scala.concurrent.{Future, Promise, Await}
import scala.concurrent.duration.Duration

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// NettySpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class NettySpec extends Specification{ def is = s2"""
Netty should:
sample mono-directional function call from client to server. $e0
sample bi-directional between server and client interaction. $e1
bi-directional heavy call between nodes. $e2
"""
	val logger = LoggerFactory.getLogger(classOf[NettySpec])
	val waitTime = Duration(3, TimeUnit.SECONDS)
	val wait1Min = Duration(60, TimeUnit.SECONDS)
	val port = new AtomicInteger(9000)

	def e0 = {
		val repository = Repository.OnMemory
		val exec = Executors.newCachedThreadPool(Asterisque.newThreadFactory("test"))
		val bridge = new Netty()

		val address = new InetSocketAddress(port.getAndIncrement)
		val server = new Node(UUID.randomUUID(), "echo", exec, EchoService, repository)
		val startup = server.listen(address, new Options()
			.set(Options.KEY_BRIDGE, bridge)
			.set(Options.KEY_CODEC, MessagePackCodec.getInstance())){ session => None }
		val stub = Await.result(startup, waitTime)

		val client = new Node(UUID.randomUUID(), "reverse", exec, new Service {}, repository)
		val future2 = client.connect(address, new Options()
			.set(Options.KEY_BRIDGE, bridge)
			.set(Options.KEY_CODEC, MessagePackCodec.getInstance()))
		val session = Await.result(future2, waitTime)
		val e = session.bind(classOf[Echo])
		val result = Await.result(e.echo("XYZ"), waitTime)

		session.close(true)
		stub.close()
		server.shutdown()
		client.shutdown()

		result === "XYZ"
	}

	def e1 = {
		val p0 = Promise[String]()
		val p1 = Promise[String]()

		val repository = Repository.OnMemory
		val exec = Executors.newCachedThreadPool(Asterisque.newThreadFactory("test"))
		val bridge = new Netty()

		val address = new InetSocketAddress(port.getAndIncrement)
		val echo = new Node(UUID.randomUUID(), "echo", exec, EchoService, repository)
		val future = echo.listen(address, new Options()
			.set(Options.KEY_BRIDGE, bridge)
			.set(Options.KEY_CODEC, MessagePackCodec.getInstance())){ session =>
			logger.info("server onAccept() callback")
			val r = session.bind(classOf[Reverse])
			p0.completeWith(r.reverse("ABCDEFG"))
		}
		val stub = Await.result(future, waitTime)

		val reverse = new Node(UUID.randomUUID(), "reverse", exec, ReverseService, repository)
		val future2 = reverse.connect(address, new Options()
			.set(Options.KEY_BRIDGE, bridge)
			.set(Options.KEY_CODEC, MessagePackCodec.getInstance()))
		val session = Await.result(future2, waitTime)
		val e = session.bind(classOf[Echo])
		val result1 = Await.result(p0.future, waitTime)
		val result2 = Await.result(e.echo("XYZ"), waitTime)
		session.close(true)

		stub.close()
		echo.shutdown()
		reverse.shutdown()
		(result1 === "GFEDCBA") and (result2 === "XYZ")
	}

	def e2 = {
		val repository = Repository.OnMemory
		val exec = Executors.newCachedThreadPool(Asterisque.newThreadFactory("test"))
		val bridge = new Netty()

		val p0 = Promise[MatchResult[Any]]()
		val address = new InetSocketAddress(port.getAndIncrement)
		val echo = new Node(UUID.randomUUID(), "echo", exec, EchoService, repository)
		val future = echo.listen(address, new Options()
			.set(Options.KEY_BRIDGE, bridge)
			.set(Options.KEY_CODEC, MessagePackCodec.getInstance())){ session =>
			val r = session.bind(classOf[Reverse])
			val f = Future.sequence((0 until 10).par.map{ i => r.reverse(i.toString).toFuture }.toList)
			p0.completeWith(f.map{ _.zipWithIndex.map{ case (s, i) => this.reverse(s) === i.toString }.reduce{ (a, b) => a and b } })
		}
		Await.ready(future, waitTime)

		val p1 = Promise[MatchResult[Any]]()
		val reverse = new Node(UUID.randomUUID(), "reverse", exec, ReverseService, repository)
		val future2 = reverse.connect(address, new Options()
			.set(Options.KEY_BRIDGE, bridge)
			.set(Options.KEY_CODEC, MessagePackCodec.getInstance())
		).map{ session =>
			val e = session.bind(classOf[Echo])
			val f = (0 until 10).par.map{ i =>
				logger.info(s"echo($i) calling")
				val future = e.echo(i.toString)
				future.onComplete{ _ => logger.info(s"echo($i) completed")}
				future.toFuture
			}.toList
			val r = p1.completeWith(Future.sequence(f).map {
				_.zipWithIndex.map { case (s, i) =>
					this.reverse(s) === i.toString
				}.reduce { (a, b) => a and b }
			}.map { r => session.close(); r })
			r
		}

		val r0 = Await.result(p0.future, waitTime)
		val r1 = Await.result(p1.future, wait1Min)
		echo.shutdown()
		reverse.shutdown()
		r0 and r1
	}

	def reverse(text:String):String = new StringBuilder(text).reverse.toString()

	trait Echo {
		@Export(100)
		def echo(text:String):CompletableFuture[String]
	}
	object EchoService extends Service with Echo {
		def echo(text:String) = {
			logger.info(s"echo(" + Debug.toString(text) + ")")
			CompletableFuture.completedFuture(text)
		}
	}
	trait Reverse {
		@Export(100)
		def reverse(text:String):CompletableFuture[String]
	}
	object ReverseService extends Service with Reverse {
		def reverse(text:String) = {
			val r = NettySpec.this.reverse(text)
			logger.info(s"reverse(" + Debug.toString(text) + "): " + Debug.toString(r))
			CompletableFuture.completedFuture(r)
		}
	}
}
