/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.netty

import java.net.SocketAddress
import javax.net.ssl.SSLContext
import org.jboss.netty.bootstrap.{Bootstrap, ServerBootstrap, ClientBootstrap}
import scala.concurrent.{ExecutionContext, Promise, Future}
import com.kazzla.asterisk._
import org.jboss.netty.channel.socket.nio.{NioServerSocketChannelFactory, NioClientSocketChannelFactory}
import org.slf4j.LoggerFactory
import java.io.Closeable
import org.jboss.netty.channel.{ChannelFuture, ChannelFutureListener}
import com.kazzla.asterisk.codec.{Codec, MsgPackCodec}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Netty
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
object Netty extends NetworkDriver {
	private[this] val logger = LoggerFactory.getLogger(getClass)

	def connect(codec:Codec, address:SocketAddress, sslContext:Option[SSLContext]):Future[Wire] = {
		val client = new ClientBootstrap(new NioClientSocketChannelFactory())
		val promise = Promise[Wire]()
		val factory = new AsteriskPipelineFactory(codec, false, sslContext, { wire =>
			logger.debug(s"onConnect($wire)")
			wire.onClosed ++ { w => shutdown(client) }
			promise.success(wire)
		})
		client.setPipelineFactory(factory)
		val future = client.connect(address)
		future.addListener(new ChannelFutureListener {
			def operationComplete(future:ChannelFuture) {
				if(future.isSuccess){
					logger.debug("operationComplete(success)")
				} else {
					logger.debug("operationComplete(failure)")
					promise.failure(future.getCause)
				}
			}
		})
		promise.future
	}

	def listen(codec:Codec, address:SocketAddress, sslContext:Option[SSLContext])(onAccept:(Wire)=>Unit):Future[Server] = {
		val server = new ServerBootstrap(new NioServerSocketChannelFactory())
		val factory = new AsteriskPipelineFactory(codec, true, sslContext, { wire =>
			logger.debug(s"onAccept($wire)")
			onAccept(wire)
		})
		server.setPipelineFactory(factory)
		val future = server.bindAsync(address)
		val promise = Promise[Server]()
		future.addListener(new ChannelFutureListener {
			def operationComplete(future:ChannelFuture) {
				if(future.isSuccess){
					logger.debug("operationComplete(success)")
					promise.success(new Server(address) { override def close() { server.shutdown() } })
				} else {
					logger.debug("operationComplete(failure)")
					promise.failure(future.getCause)
				}
			}
		})
		promise.future
	}

	private[this] def shutdown(bootstrap:Bootstrap):Unit = {
		ExecutionContext.Implicits.global.execute(new Runnable(){
			def run(){
				bootstrap.shutdown()
			}
		})
	}

}
