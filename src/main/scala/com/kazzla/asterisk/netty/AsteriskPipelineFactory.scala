/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.netty

import javax.net.ssl.SSLContext
import org.slf4j.LoggerFactory
import scala.Some
import java.util.concurrent.atomic.AtomicBoolean
import com.kazzla.asterisk._
import com.kazzla.asterisk.codec.Codec
import io.netty.handler.codec.{MessageToByteEncoder, ByteToMessageDecoder}
import io.netty.channel.{ChannelInitializer, ChannelHandlerContext}
import io.netty.buffer.ByteBuf
import java.util.{List => JList}
import io.netty.handler.ssl.SslHandler
import io.netty.channel.socket.SocketChannel

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// AsteriskPipelineFactory
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class AsteriskPipelineFactory(codec:Codec, isServer:Boolean, sslContext:Option[SSLContext], onWireCreate:(Wire)=>Unit) extends ChannelInitializer[SocketChannel] {
	private[this] val logger = LoggerFactory.getLogger(classOf[AsteriskPipelineFactory])

	private[this] def sym = if(isServer) "S" else "C"

	override def initChannel(ch:SocketChannel) = {
		logger.trace(s"$sym: initChannel($ch)")
		val pipeline = ch.pipeline()
		val sslHandler = sslContext match {
			case Some(s) =>
				val engine = s.createSSLEngine()
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
	/*
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
	 */

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

/**
 * 処理を一度だけ実行する。
 */
private[netty] class Once {
	private val first = new AtomicBoolean(true)
	def apply(f: =>Unit) = if(first.compareAndSet(true, false)){
		f
	}
}
