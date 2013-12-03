/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.netty

import org.jboss.netty.channel._
import javax.net.ssl.{SSLEngine, SSLSession, SSLContext}
import org.slf4j.LoggerFactory
import org.jboss.netty.handler.ssl.SslHandler
import scala.Some
import org.jboss.netty.handler.codec.oneone.OneToOneEncoder
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}
import org.jboss.netty.handler.codec.frame.FrameDecoder
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{Promise, Future}
import java.net.{InetSocketAddress, SocketAddress}
import com.kazzla.asterisk
import com.kazzla.asterisk._
import java.io.{IOException, Closeable}
import com.kazzla.asterisk.codec.Codec

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// AsteriskPipelineFactory
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class AsteriskPipelineFactory(codec:Codec, isServer:Boolean, sslContext:Option[SSLContext], onWireCreate:(Wire)=>Unit) extends ChannelPipelineFactory {
	private[this] val logger = LoggerFactory.getLogger(classOf[AsteriskPipelineFactory])
	def getPipeline = {
		val pipeline = Channels.pipeline()
		val sslHandler = sslContext match {
			case Some(s) =>
				val engine = s.createSSLEngine()
				engine.setUseClientMode(! isServer)
				engine.setNeedClientAuth(true)
				if(logger.isTraceEnabled){
					logger.trace(s"CipherSuites: ${engine.getEnabledCipherSuites.mkString(",")}")
					logger.trace(s"Protocols: ${engine.getEnabledProtocols.mkString(",")}")
				}
				val handler = new SslHandler(engine)
				pipeline.addLast("tls", handler)
				Some(handler)
			case None =>
				None
		}
		pipeline.addLast("com.kazzla.asterisk.frame.encoder", new MessageEncoder(codec))
		pipeline.addLast("com.kazzla.asterisk.frame.decoder", new MessageDecoder(codec))
		pipeline.addLast("com.kazzla.asterisk.service", new WireConnect(sslHandler))
		pipeline
	}

	private[this] class WireConnect(sslHandler:Option[SslHandler]) extends SimpleChannelHandler {
		private[this] val logger = LoggerFactory.getLogger(classOf[WireConnect])

		@volatile
		private[this] var wire:Option[NettyWire] = None

		override def channelConnected(ctx:ChannelHandlerContext, e:ChannelStateEvent):Unit = {
			assert(wire.isEmpty)
			val promise = Promise[Option[SSLSession]]()
			sslHandler match {
				case Some(h) =>
					h.handshake().addListener(new ChannelFutureListener {
						def operationComplete(future:ChannelFuture) = {
							val session = h.getEngine.getSession
							if(session.isValid){
								promise.success(Some(session))
								logger.debug(s"tls handlshake finished: ${session.getPeerCertificates.map{ _.getType }}")
							} else {
								promise.failure(new Exception("tls handlshake failure: invalid session"))
								logger.debug("tls handlshake failure: invalid session")
							}
						}
					})
				case None =>
					promise.success(None)
			}
			wire = Some(NettyWire(e.getChannel.getRemoteAddress, isServer, promise.future, ctx))
			super.channelConnected(ctx, e)

			onWireCreate(wire.get)
		}

		override def channelClosed(ctx:ChannelHandlerContext, e:ChannelStateEvent):Unit = {
			close()
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

			e.getMessage match {
				case msg:Message => wire.foreach { _._receive(msg) }
				case _ => None
			}

			super.messageReceived(ctx, e)
		}

		override def exceptionCaught(ctx:ChannelHandlerContext, e:ExceptionEvent){
			logger.debug("exception caught", e.getCause)
			e.getChannel.getAttachment match {
				case s:Session => s.close()
				case _ => None
			}
		}

		private[this] def close():Unit = {
			wire.foreach{ _.close() }
			wire = None
		}

	}

	private[netty] case class NettyWire(address:SocketAddress, override val isServer:Boolean, override val tls:Future[Option[SSLSession]], context:ChannelHandlerContext) extends Wire {

		override val peerName = address match {
			case i:InetSocketAddress =>
				s"${i.getAddress.getHostAddress}:${i.getPort}"
			case s => s.toString
		}

		def send(m:Message):Unit = if(! isClosed){
			val ch = context.getChannel
			val event = new DownstreamMessageEvent(ch, Channels.future(ch), m, ch.getRemoteAddress)
			context.sendDownstream(event)
		} else {
			throw new IOException(s"cannot send on closed channel: $m")
		}

		override def close():Unit = if(! isClosed){
			asterisk.close(new Closeable { def close() { context.getChannel.close() }} )
			super.close()
		}

		private[netty] def _receive(msg:Message):Unit = receive(msg)

	}

}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// MessageEncoder
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
private[netty] class MessageEncoder(codec:Codec) extends OneToOneEncoder {
	def encode(ctx:ChannelHandlerContext, channel:Channel, msg:Any):AnyRef = msg match {
		case message:Message =>
			val buffer = codec.encode(message)
			ChannelBuffers.copiedBuffer(buffer)
		case unknown:AnyRef => unknown
	}
}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// MessageDecoder
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
private[netty] class MessageDecoder(codec:Codec) extends FrameDecoder {
	def decode(ctx:ChannelHandlerContext, channel:Channel, b:ChannelBuffer):AnyRef = {
		val buffer = b.toByteBuffer
		codec.decode(buffer) match {
			case Some(frame) =>
				b.skipBytes(buffer.position())
				frame
			case None =>
				null
		}
	}
}

/**
 * 処理を一度だけ実行する。
 */
private[netty] class Once {
	private val first = new AtomicBoolean(true)
	def apply(f: =>Unit) = if(first.compareAndSet(true, false)){
		f
	}
}
