/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.util.concurrent.Executors
import scala.io.Source
import java.net.InetSocketAddress
import java.io.PrintWriter
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext.Implicits.global

object Sample {
	val logger = LoggerFactory.getLogger(Sample.getClass)
	val executor = Executors.newCachedThreadPool()

	trait NameServer {
		@Export(10)
		def lookup(name:String):Int
	}

	trait LogServer {
		@Export(10)
		def info(msg:String):Unit
		@Export(20)
		def error(msg:String):Unit
		@Export(value=30,stream=true)
		def dump(msg:String):Unit
	}


	object Node1 {

		val ns = Node("NameServer").serve(new Service with NameServer {
			def lookup(name:String):Int = Session() match {
				case Some(session) =>

					logger.info("calling log.info(\"hoge\")")
					val log = session.bind(classOf[LogServer])
					log.info("hoge")

					logger.info("calling log.dump() with line from 0 to 9")
					val pipe = session.open(30, "hoge")
					val out = new PrintWriter(new PipeOutputStream(pipe))
					(0 until 10).foreach{ i => out.println(i) }
					out.close()

					logger.info("returning 100")
					100
				case None =>
					throw new Exception()
			}
		}).build()

		val server = {
			val future = ns.listen(new InetSocketAddress(7777), None)
			Await.result(future, Duration.Inf)
		}
	}

	object Node2 {

		val logging = Node("LoggingServer").serve(new Service with LogServer {
			def error(msg:String) { Console.out.print(s"ERROR: $msg\n") }
			def info(msg:String)  { Console.out.print(s"INFO : $msg\n") }
			def dump(msg:String):Unit = Pipe() match {
				case Some(pipe) =>
					Console.out.print(s"DUMP: $msg\n")
					Source.fromInputStream(pipe.in).getLines().foreach { line =>
						Console.out.println(line)
					}
				case None =>
					throw new Exception()
			}
		}).build()

		val future = logging.connect(new InetSocketAddress(7777), None)

		val session = Await.result(future, Duration.Inf)
		val ns = session.bind(classOf[NameServer])
		Console.println(ns.lookup("www.google.com"))
	}

	def main(args:Array[String]):Unit = {
		Node1
		Node2
		Node1.ns.shutdown()
		Node2.logging.shutdown()
		executor.shutdown()
	}

}
