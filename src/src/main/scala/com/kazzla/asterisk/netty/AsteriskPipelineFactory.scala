/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.netty

import org.jboss.netty.channel._
import javax.net.ssl.{SSLSession, SSLContext}
import org.slf4j.LoggerFactory
import org.jboss.netty.handler.ssl.SslHandler
import scala.Some
import org.jboss.netty.handler.codec.oneone.OneToOneEncoder
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}
import org.jboss.netty.handler.codec.frame.FrameDecoder
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{Promise, Future}
import java.net.SocketAddress
import com.kazzla.asterisk
import com.kazzla.asterisk._
import java.io.Closeable
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
		val sslSession = Promise[Option[SSLSession]]()    // SSLハンドシェイク完了用
		val pipeline = Channels.pipeline()
		sslContext match {
			case Some(s) =>
				val engine = s.createSSLEngine()
				engine.setUseClientMode(! isServer)
				engine.setNeedClientAuth(true)
				if(logger.isTraceEnabled){
					logger.trace(s"CipherSuites: ${engine.getEnabledCipherSuites.mkString(",")}")
					logger.trace(s"Protocols: ${engine.getEnabledProtocols.mkString(",")}")
				}
				pipeline.addLast("tls", new SslHandler(engine))   // TODO ハンドシェイクが終わったら SSLSession を参照したい
				sslSession.success(Some(engine.getSession))       // TODO まだハンドシェイクが終わっていない
			case None =>
				sslSession.success(None)
		}
		pipeline.addLast("com.kazzla.asterisk.frame.encoder", new MessageEncoder(codec))
		pipeline.addLast("com.kazzla.asterisk.frame.decoder", new MessageDecoder(codec))
		pipeline.addLast("com.kazzla.asterisk.service", new WireConnect(sslSession.future))
		pipeline
	}

	private[this] class WireConnect(ssl:Future[Option[SSLSession]]) extends SimpleChannelHandler {
		private[this] val logger = LoggerFactory.getLogger(classOf[WireConnect])

		@volatile
		private[this] var wire:Option[NettyWire] = None

		override def channelConnected(ctx:ChannelHandlerContext, e:ChannelStateEvent):Unit = {
			assert(wire.isEmpty)
			wire = Some(NettyWire(e.getChannel.getRemoteAddress, isServer, ssl, ctx))
			super.channelConnected(ctx, e)

			// TODO ここで onConnect 呼んでも SSL ハンドシェイクが済んでいないはず
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

		override val peerName = address.getName

		def send(m:Message):Unit = {
			val ch = context.getChannel
			val event = new DownstreamMessageEvent(ch, Channels.future(ch), m, ch.getRemoteAddress)
			context.sendDownstream(event)
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
