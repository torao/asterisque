/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.netty

import java.net.SocketAddress
import javax.net.ssl.SSLContext
import org.jboss.netty.bootstrap.{ServerBootstrap, ClientBootstrap, Bootstrap}
import scala.concurrent.{Promise, Future}
import com.kazzla.asterisk.Wire
import org.jboss.netty.channel.socket.nio.{NioServerSocketChannelFactory, NioClientSocketChannelFactory}
import org.jboss.netty.channel.{ChannelFuture, ChannelFutureListener}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Netty
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
object Netty {

	def connect(address:SocketAddress, ssl:Option[SSLContext]):(Bootstrap, Future[Wire]) = {
		logger.trace(s"connect(${address.getName},$ssl)")
		val promise = Promise[Wire]()
		val channelFactory = new NioClientSocketChannelFactory()
		val client = new ClientBootstrap(channelFactory)
		client.setPipelineFactory(new Factory(false, ssl))
		client.connect(address).addListener(new ChannelFutureListener {
			def operationComplete(future:ChannelFuture) {
				if(future.isSuccess){
					promise.success(NettyWire.this)
				} else {
					promise.failure(future.getCause)
				}
			}
		})
		(client, promise.future)
	}

	def listen(address:SocketAddress, ssl:Option[SSLContext]):(Bootstrap, Future[Wire]) = {
		logger.trace(s"listen(${address.getName},$ssl)")
		val promise = Promise[Wire]()
		val channelFactory = new NioServerSocketChannelFactory()
		val server = new ServerBootstrap(channelFactory)
		server.setPipelineFactory(new Factory(true, ssl))
		server.bindAsync(address).addListener(new ChannelFutureListener {
			def operationComplete(future:ChannelFuture) {
				if(future.isSuccess){
					promise.success(NettyWire.this)
				} else {
					promise.failure(future.getCause)
				}
			}
		})
		(server, promise.future)
	}
}
