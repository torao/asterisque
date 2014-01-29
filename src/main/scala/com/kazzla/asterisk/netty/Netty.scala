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
import io.netty.channel.{ChannelHandlerContext, ChannelInitializer, ChannelOption}
import io.netty.util.concurrent.GenericFutureListener
import io.netty.util.concurrent.{Future => NFuture}
import java.net.SocketAddress
import javax.net.ssl.SSLContext
import org.slf4j.LoggerFactory
import scala.concurrent.{Future, Promise}
import io.netty.channel.socket.SocketChannel
import io.netty.handler.ssl.SslHandler
import io.netty.handler.codec.{ByteToMessageDecoder, MessageToByteEncoder}
import io.netty.buffer.ByteBuf
import java.util.{List => JList}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Netty
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * ソケット I/O 実装に Netty を使用したブリッジです。非同期 SSL に対応しています。
 *
 * @author Takami Torao
 */
object Netty extends Bridge {
	private[this] val logger = LoggerFactory.getLogger(getClass)

	// ==============================================================================================
	// 接続の実行
	// ==============================================================================================
	/**
	 * 指定されたアドレスへの接続を行います。
	 *
	 * @param codec Wire 上で使用するコーデック
	 * @param address 接続先のアドレス
	 * @param sslContext クライアント認証を行うための SSL 証明書 (Noneの場合は非SSL接続)
	 * @return Wire の Future
	 */
	def connect(codec:Codec, address:SocketAddress, sslContext:Option[SSLContext]):Future[Wire] = {
		val group = new NioEventLoopGroup()
		val client = new Bootstrap()
		val promise = Promise[Wire]()
		val factory = new Netty(codec, false, sslContext, { wire =>
			logger.debug(s"onConnect($wire)")
			wire.onClosed ++ { w => shutdown(client) }
			promise.success(wire)
		})
		client
			.group(group)
			.channel(classOf[NioSocketChannel])
			.remoteAddress(address)
			.option(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
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

	// ==============================================================================================
	// 接続受付の実行
	// ==============================================================================================
	/**
	 * 指定されたアドレス上で接続の受け付けを開始します。
	 *
	 * @param codec Wire 上で使用するコーデック
	 * @param address バインド先のアドレス
	 * @param sslContext サーバ認証を行うための SSL 証明書 (Noneの場合は非SSL接続)
	 * @param onAccept サーバ上で新しい接続が発生した時のコールバック
	 * @return Server の Future
	 */
	def listen(codec:Codec, address:SocketAddress, sslContext:Option[SSLContext])(onAccept:(Wire)=>Unit):Future[Server] = {
		val factory = new Netty(codec, true, sslContext, { wire =>
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

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Netty
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
private[netty] class Netty(codec:Codec, isServer:Boolean, sslContext:Option[SSLContext], onWireCreate:(Wire)=>Unit) extends ChannelInitializer[SocketChannel] {
	private[this] val logger = LoggerFactory.getLogger(classOf[Netty])

	private[this] lazy val sym = if(isServer) "S" else "C"

	// ==============================================================================================
	//
	// ==============================================================================================
	override def initChannel(ch:SocketChannel) = {
		logger.trace(s"$sym: initChannel($ch)")
		val pipeline = ch.pipeline()
		val sslHandler = sslContext match {
			case Some(s) =>
				val engine =s.createSSLEngine()   // この時点では ch.remoteAddress() が null なためリモート情報は設定できない
				engine.setUseClientMode(! isServer)
				engine.setNeedClientAuth(true)
				if(logger.isTraceEnabled){
					logger.trace(s"$sym: CipherSuites: ${engine.getEnabledCipherSuites.mkString(",")}")
					logger.trace(s"$sym: Protocols: ${engine.getEnabledProtocols.mkString(",")}")
				}
				val handler = new SslHandler(engine)
				pipeline.addLast("tls", handler)
				Some(handler)
			case None =>
				logger.trace(s"$sym: insecure connection")
				None
		}
		pipeline.addLast("com.kazzla.asterisk.frame.encoder", new MessageEncoder(codec))
		pipeline.addLast("com.kazzla.asterisk.frame.decoder", new MessageDecoder(codec))
		pipeline.addLast("com.kazzla.asterisk.service", new WireConnect(sslHandler, isServer, onWireCreate))
	}
}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// MessageEncoder
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
private[netty] class MessageEncoder(codec:Codec) extends MessageToByteEncoder[Message] {
	def encode(ctx:ChannelHandlerContext, msg:Message, b:ByteBuf):Unit = {
		b.writeBytes(codec.encode(msg))
	}
}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// MessageDecoder
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
private[netty] class MessageDecoder(codec:Codec) extends ByteToMessageDecoder {
	def decode(ctx:ChannelHandlerContext, b:ByteBuf, out:JList[Object]):Unit = {
		val buffer = b.nioBuffer()
		codec.decode(buffer) match {
			case Some(msg) =>
				b.skipBytes(buffer.position())
				out.add(msg)
			case None => None
		}
	}
}
