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
import scala.collection.JavaConversions._
import java.util
import com.kazzla.asterisk.{Wire, Message, Session}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// BirpcChannelPipelineFactory
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class AsteriskPipelineFactory(onConnect:(Wire)=>Unit, isServer:Boolean = false, sslContext:Option[SSLContext] = None) extends ChannelPipelineFactory {
	private[this] val logger = LoggerFactory.getLogger(classOf[AsteriskPipelineFactory])
	def this(onConnect:(Wire)=>Unit, sslContext:SSLContext) = this(onConnect, false, Some(sslContext))
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
		pipeline.addLast("com.kazzla.asterisk.frame.encoder", new MessageEncoder())
		pipeline.addLast("com.kazzla.asterisk.frame.decoder", new MessageDecoder())
		pipeline.addLast("com.kazzla.asterisk.service", new WireConnect(onConnect, isServer, None))   // TODO SSLSession の渡し方は? この時点でまだハンドシェイクできてない
		pipeline
	}
}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// WireConnect
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
private[netty] class WireConnect(onConnect:(Wire)=>Unit, isServer:Boolean, ssl:Option[SSLSession]) extends SimpleChannelHandler {
	private[this] val logger = LoggerFactory.getLogger(classOf[WireConnect])

	@volatile
	private[this] var connection:Option[ChannelHandlerContext] = None

	private[this] var wire:Option[NettyWire] = None

	override def channelConnected(ctx:ChannelHandlerContext, e:ChannelStateEvent):Unit = {
		assert(wire.isEmpty)
		connection = Some(ctx)
		wire = Some(new NettyWire(isServer, e.getChannel.getRemoteAddress, ssl))
		super.channelConnected(ctx, e)

		onConnect(wire.get) // ここで onConnect 呼んでも SSL ハンドシェイクが済んでいない
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

		wire.foreach{ _.onReceive(e.getMessage.asInstanceOf[Message]) }

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
		connection.foreach{ _.getChannel.close() }
		connection = None
		wire.foreach{ _.close() }
		wire = None
	}

}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// MessageEncoder
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
private[netty] class MessageEncoder extends OneToOneEncoder {
	def encode(ctx:ChannelHandlerContext, channel:Channel, msg:Any):AnyRef = msg match {
		case packet:Message =>
			val buffer = Message.encode(packet)
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
private[netty] class MessageDecoder extends FrameDecoder {
	def decode(ctx:ChannelHandlerContext, channel:Channel, b:ChannelBuffer):AnyRef = {
		val buffer = b.toByteBuffer
		Message.decode(buffer) match {
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
