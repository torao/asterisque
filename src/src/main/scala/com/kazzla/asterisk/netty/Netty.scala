/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.netty

import java.net.SocketAddress
import javax.net.ssl.SSLContext
import org.jboss.netty.bootstrap.{ServerBootstrap, ClientBootstrap}
import scala.concurrent.{Promise, Future}
import com.kazzla.asterisk._
import org.jboss.netty.channel.socket.nio.{NioServerSocketChannelFactory, NioClientSocketChannelFactory}
import org.slf4j.LoggerFactory
import java.io.Closeable
import org.jboss.netty.channel.{ChannelFuture, ChannelFutureListener}
import com.kazzla.asterisk.codec.MsgPackCodec

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Netty
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
object Netty extends NetworkDriver {
	private[this] val logger = LoggerFactory.getLogger(getClass)

	def connect(address:SocketAddress, sslContext:Option[SSLContext]):Future[Wire] = {
		val promise = Promise[Wire]()
		val factory = new AsteriskPipelineFactory(MsgPackCodec, false, sslContext, { wire =>
			logger.debug(s"onWireCreate($wire)")
			promise.success(wire)
		})
		val client = new ClientBootstrap(new NioClientSocketChannelFactory())
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

	def listen(address:SocketAddress, sslContext:Option[SSLContext])(onAccept:(Wire)=>Unit):Server = {
		val factory = new AsteriskPipelineFactory(MsgPackCodec, true, sslContext, { wire =>
			logger.debug(s"onWireCreate($wire)")
			onAccept(wire)
		})
		val server = new ServerBootstrap(new NioServerSocketChannelFactory())
		server.setPipelineFactory(factory)
		val future = server.bindAsync(address)
		future.addListener(new ChannelFutureListener {
			def operationComplete(future:ChannelFuture) {
				if(future.isSuccess){
					logger.debug("operationComplete(success)")
				} else {
					logger.debug("operationComplete(failure)")
				}
			}
		})
		new Server(address) {
			override def close() { server.shutdown() }
		}
	}

}
