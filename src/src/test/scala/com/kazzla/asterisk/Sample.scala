/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.util.concurrent.Executors
import scala.io.Source
import com.kazzla.asterisk.netty.Netty
import java.net.InetSocketAddress
import java.io.PrintWriter

object Sample {
	val executor = Executors.newCachedThreadPool()

	object Node1 {

		val ns = Node("NameServer").serve(new NameServer {
			def lookup(name:String):Int = Session() match {
				case Some(session) =>
					val log = session.getRemoteInterface(classOf[LogServer])
					log.info("hoge")

					val pipe = session2.open(30, "hoge")
					val out = new PrintWriter(pipe.out)
					(0 until 10).foreach{ i => out.println(i) }
					out.close()

					100
			}
		}).runOn(executor).build()

		Netty.listen(new InetSocketAddress(7777), ns)
	}

	object Node2 {

		val logging = Node("LoggingServer").serve(new LogServer {
			def error(msg:String) { Console.out.print(s"INFO : $msg\n") }
			def info(msg:String)  { Console.out.print(s"ERROR: $msg\n") }
			def dump(msg:String):Unit = Pipe.apply() match {
				case Some(pipe) =>
					Console.out.print(s"DUMP: $msg\n")
					Source.fromInputStream(pipe.in).getLines().foreach { line =>
						Console.out.println(line)
					}
			}
		}).runOn(executor).build()

		val session:Session = Netty.connect(new InetSocketAddress(7777), logging)

		val ns = session1.getRemoteInterface(classOf[NameServer])
		Console.println(ns.lookup("www.google.com"))
	}

}

trait NameServer {
	@Export(10)
	def lookup(name:String):Int
}

trait LogServer {
	@Export(10)
	def info(msg:String):Unit
	@Export(20)
	def error(msg:String):Unit
	@Export(30)
	def dump(msg:String):Unit
}
