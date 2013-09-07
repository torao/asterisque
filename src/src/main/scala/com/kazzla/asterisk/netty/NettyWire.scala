/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.netty

import com.kazzla.asterisk._
import org.jboss.netty.channel._
import org.slf4j.LoggerFactory
import java.util
import org.jboss.netty.handler.ssl.SslHandler
import scala.Some
import scala.collection.JavaConversions._
import javax.net.ssl.{SSLSession, SSLContext}
import java.util.concurrent.atomic.AtomicBoolean
import org.jboss.netty.bootstrap.Bootstrap
import java.net.SocketAddress
import scala.concurrent.Future
import java.io.IOException

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// NettyWire
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
private[netty] class NettyWire(isServer:Boolean, address:SocketAddress, ssl:Option[SSLSession]) extends Wire {
	import NettyWire.logger

	@volatile
	private[this] var connection:Option[ChannelHandlerContext] = None

	private[this] var bootstrap:Option[Bootstrap] = None

	private[this] val disconnected = new AtomicBoolean(false)
	private[this] val queue = new util.ArrayList[Message]()

	lazy val peerName = address.getName

	lazy val tls:Option[SSLSession] = ssl

	def send(m:Message):Unit = connection match {
		case Some(ctx) =>
			val ch = ctx.getChannel
			val event = new DownstreamMessageEvent(ch, Channels.future(ch), m, ch.getRemoteAddress)
			ctx.sendDownstream(event)
		case None =>
			queue.add(m)
	}

	def open():Future[Wire] = if(disconnected.compareAndSet(true, false)){
		val (bootstrap, future) = if(isServer){
			connect(address, ssl)
		} else {
			listen(address, ssl)
		}
		this.bootstrap = Some(bootstrap)
		future
	} else {
		throw new IOException(s"wire is already opened: ${address.getName}")
	}

	def close():Unit = if(disconnected.compareAndSet(false, true)){
		connection.foreach{ _.getChannel.close() }
		connection = None
		bootstrap.foreach{ _.shutdown() }
		bootstrap = None
		queue.clear()
	}

	private[this] class Factory(isServer:Boolean, sslContext:Option[SSLContext]) extends ChannelPipelineFactory{
		private[this] val logger = LoggerFactory.getLogger(classOf[AsteriskPipelineFactory])
		def getPipeline = {
			val pipeline = Channels.pipeline()
			sslContext.foreach{ s =>
				val engine = s.createSSLEngine()
				engine.setUseClientMode(! isServer)
				engine.setNeedClientAuth(true)
				if(logger.isTraceEnabled){
					logger.trace(s"CipherSuites: ${engine.getEnabledCipherSuites.mkString(",")}")
					logger.trace(s"Protocols: ${engine.getEnabledProtocols.mkString(",")}")
				}
				pipeline.addLast("tls", new SslHandler(engine))
			}
			pipeline.addLast("com.kazzla.birpc.frame.encoder", new MessageEncoder())
			pipeline.addLast("com.kazzla.birpc.frame.decoder", new MessageDecoder())
			pipeline.addLast("com.kazzla.birpc.service", new Handler())
			pipeline
		}
	}

	private[this] class Handler extends SimpleChannelHandler {

		override def channelConnected(ctx:ChannelHandlerContext, e:ChannelStateEvent):Unit = {
			connection = Some(ctx)
			queue.foreach { msg => send(msg) }
			queue.clear()
			super.channelConnected(ctx, e)
		}

		override def channelClosed(ctx:ChannelHandlerContext, e:ChannelStateEvent):Unit = {
			connection = None
			onClosed(NettyWire.this)
			super.channelClosed(ctx, e)
		}

		private val authed = new Once()
		override def messageReceived(ctx:ChannelHandlerContext, e:MessageEvent):Unit = {
			authed{
				Option(e.getChannel.getPipeline.get(classOf[SslHandler])).foreach { s =>
					val session = s.getEngine.getSession
					if(logger.isTraceEnabled){
						logger.trace(s"CipherSuite   : ${session.getCipherSuite}")
						logger.trace(s"LocalPrincipal: ${session.getLocalPrincipal}")
						logger.trace(s"PeerHost      : ${session.getPeerHost}")
						logger.trace(s"PeerPort      : ${session.getPeerPort}")
						logger.trace(s"PeerPrincipal : ${session.getPeerPrincipal}")
					}
				}
			}

			onReceive(e.getMessage.asInstanceOf[Message])

			super.messageReceived(ctx, e)
		}

		override def exceptionCaught(ctx:ChannelHandlerContext, e:ExceptionEvent){
			logger.debug("exception caught", e.getCause)
			e.getChannel.getAttachment match {
				case s:Session => s.close()
				case _ => None
			}
		}
	}

}

object NettyWire {
	private[NettyWire] val logger = LoggerFactory.getLogger(classOf[NettyWire])

	def connect(address:SocketAddress, ssl:Option[SSLContext]):Wire = {
		new NettyWire(false, address, ssl)
	}

	def listen(address:SocketAddress, ssl:Option[SSLContext]):Wire = {
		new NettyWire(true, address, ssl)
	}

}