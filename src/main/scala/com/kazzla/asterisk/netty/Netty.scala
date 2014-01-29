/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.netty

import com.kazzla.asterisk._
import com.kazzla.asterisk.codec.Codec
import io.netty.bootstrap.{ServerBootstrap, Bootstrap}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.channel.ChannelOption
import io.netty.util.concurrent.GenericFutureListener
import io.netty.util.concurrent.{Future => NFuture}
import java.net.SocketAddress
import javax.net.ssl.SSLContext
import org.slf4j.LoggerFactory
import scala.concurrent.{Future, Promise}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Netty
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
object Netty extends Bridge {
	private[this] val logger = LoggerFactory.getLogger(getClass)

	def connect(codec:Codec, address:SocketAddress, sslContext:Option[SSLContext]):Future[Wire] = {
		val group = new NioEventLoopGroup()
		val client = new Bootstrap()
		val promise = Promise[Wire]()
		val factory = new AsteriskPipelineFactory(codec, false, sslContext, { wire =>
			logger.debug(s"onConnect($wire)")
			wire.onClosed ++ { w => shutdown(client) }
			promise.success(wire)
		})
		client
			.group(group)
			.channel(classOf[NioSocketChannel])
			.remoteAddress(address)
			.option(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)    // TODO 確認
			.handler(factory)
		client.connect(address).addListener(new GenericFutureListener[NFuture[Any]] {
			def operationComplete(future:NFuture[Any]):Unit = {
				if(future.isSuccess){
					logger.debug("connection success")
				} else {
					logger.debug("connection failure")
					promise.failure(future.cause())
				}
			}
		})
		promise.future
	}

	def listen(codec:Codec, address:SocketAddress, sslContext:Option[SSLContext])(onAccept:(Wire)=>Unit):Future[Server] = {
		val factory = new AsteriskPipelineFactory(codec, true, sslContext, { wire =>
			logger.debug(s"onAccept($wire)")
			onAccept(wire)
		})
		val masterGroup = new NioEventLoopGroup()
		val workerGroup = new NioEventLoopGroup()
		val server = new ServerBootstrap()
		server
			.group(masterGroup, workerGroup)
			.channel(classOf[NioServerSocketChannel])
			.localAddress(address)
			.option(ChannelOption.SO_BACKLOG, java.lang.Integer.valueOf(100))
			.childOption(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
			.childHandler(factory)

		val promise = Promise[Server]()
		server.bind().addListener(new GenericFutureListener[NFuture[Any]] {
			def operationComplete(future:NFuture[Any]):Unit = {
				if(future.isSuccess){
					logger.debug("operationComplete(success)")
					promise.success(new Server(address) {
						override def close() {
							logger.debug("closing netty server bootstrap")
							masterGroup.shutdownGracefully()
							workerGroup.shutdownGracefully()
						}
					})
				} else {
					logger.debug("operationComplete(failure)")
					promise.failure(future.cause())
				}
			}
		})
		promise.future
	}

	private[this] def shutdown(bootstrap:Bootstrap):Unit = {
		logger.debug("closing netty client bootstrap")
		bootstrap.group().shutdownGracefully()
	}

	private[this] def shutdown(bootstrap:ServerBootstrap):Unit = {
		logger.debug("closing netty server bootstrap")
		bootstrap.group().shutdownGracefully()
		bootstrap.childGroup().shutdownGracefully()
	}

}
