/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import org.slf4j.LoggerFactory
import java.net.SocketAddress
import com.sun.net.ssl.SSLContext

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Node
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class Node private[Node](name:String, executor:Executor, initService:Object){
	import Node._

	private[this] var service = initService

	private[this] val sessions = new AtomicReference(Seq[Session]())

	val onConnect = new EventHandlers[Session]()

	def service_=(newService:Object):Object = {
		val old = service
		service = newService
		sessions.get().foreach{ _.service_=(newService) }
		old
	}

	/*
	def listen(address:SocketAddress):Unit = listen(address, defaultTls)
	def listen(address:SocketAddress, tls:SSLContext):Unit = listen(address, Option(tls))
	private[this] def listen(address:SocketAddress, ssl:Option[SSLContext]):Unit = {
		logger.trace(s"listen(${address.getName},$ssl)")
		val channelFactory = new NioServerSocketChannelFactory()
		val bootstrap:Bootstrap = {
			val server = new ServerBootstrap(channelFactory)
			server.setPipelineFactory(new BirpcChannelPipelineFactory(newSession, true, ssl))
			server.bind(address)
			server
		}
		add(bootstrap)
	}

	def connect(address:SocketAddress)(f:(Session)=>Unit):Session = connect(address, defaultTls, f)
	def connect(address:SocketAddress, tls:SSLContext)(f:(Session)=>Unit):Session = connect(address, Option(tls), f)
	private[this] def connect(address:SocketAddress, ssl:Option[SSLContext], f:(Session)=>Unit):Session = {
		logger.trace(s"connect(${address.getName},$ssl)")
		val channelFactory = new NioClientSocketChannelFactory()
		val (bootstrap:Bootstrap, channel:Channel) = {
			val client = new ClientBootstrap(channelFactory)
			client.setPipelineFactory(new BirpcChannelPipelineFactory(newSession, false, ssl))
			val future = client.connect(address)
			future.awaitUninterruptibly()
			if(! future.isSuccess){
				throw future.getCause
			}
			(client, future.getChannel)
		}
		add(bootstrap)
		channel.getAttachment.asInstanceOf[Session]
	}

	*/

	def shutdown():Unit = {
		sessions.get().foreach{ _.close() }
		logger.trace(s"shutdown():$name")
	}

	/*
	@tailrec
	private[this] def add(con:Bootstrap):Unit = {
		val n = bootstraps.get()
		if(! bootstraps.compareAndSet(n, n.+:(con))){
			add(con)
		}
	}
	*/

	@tailrec
	private[this] def add(s:Session):Unit = {
		val n = sessions.get()
		if(! sessions.compareAndSet(n, n.+:(s))){
			add(s)
		}
	}

	@tailrec
	private[this] def remove(s:Session):Unit = {
		val n = sessions.get()
		if(! sessions.compareAndSet(n, n.filter{ _ != s })){
			remove(s)
		}
	}

	def connect(wire:Wire):Session = {
		logger.trace(s"newSession($wire):$name")
		val s = new Session(s"$name[${wire.peerName}]", executor, service, wire)
		add(s)
		s.onClosed ++ remove
		s
	}
}

object Node {
	private[Node] val logger = LoggerFactory.getLogger(classOf[Node])

	def apply(name:String):Builder = new Builder(name)

	class Builder private[Node](name:String) {
		private var executor:Executor = scala.concurrent.ExecutionContext.global
		private var service:Object = new Object()

		def runOn(exec:Executor):Builder = {
			this.executor = executor
			this
		}

		def serve(service:Object):Builder = {
			this.service = service
			this
		}

		def build():Node = new Node(name, executor, service)

	}
}
