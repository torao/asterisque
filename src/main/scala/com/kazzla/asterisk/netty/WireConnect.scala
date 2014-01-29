/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.netty

import io.netty.handler.ssl.SslHandler
import io.netty.channel.{Channel, ChannelHandlerContext, SimpleChannelInboundHandler}
import com.kazzla.asterisk.Message
import org.slf4j.LoggerFactory
import scala.concurrent.Promise
import javax.net.ssl.SSLSession
import io.netty.util.concurrent.{Future => NFuture, GenericFutureListener}
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.security.cert.X509Certificate

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// WireConnect
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
private[netty] class WireConnect(sslHandler:Option[SslHandler], isServer:Boolean, onWireCreate:(NettyWire)=>Unit) extends SimpleChannelInboundHandler[Message] {
	import WireConnect._

	private[this] def sym = if(isServer) "S" else "C"

	@volatile
	private[this] var wire:Option[NettyWire] = None

	private[this] val id = WireConnect.seq.getAndIncrement

	override def channelActive(ctx:ChannelHandlerContext):Unit = {
		logger.trace(s"$sym[$id]: channelActive($ctx)")
		assert(wire.isEmpty)
		val promise = Promise[Option[SSLSession]]()
		sslHandler match {
			case Some(h) =>
				h.handshakeFuture().addListener(new GenericFutureListener[NFuture[Channel]] {
					def operationComplete(future:NFuture[Channel]):Unit = {
						val session = h.engine().getSession
						if(session.isValid){
							promise.success(Some(session))
							logger.debug(s"$sym[$id]: tls handshake success: ${session.getPeerCertificates.map{ case c:X509Certificate => c.getSubjectDN.getName }.mkString(", ")}")
						} else {
							promise.failure(new IOException("tls handshake failure: invalid session"))
							logger.debug(s"$sym[$id]: tls handshake failure: invalid session")
						}
					}
				})
			case None =>
				promise.success(None)
		}
		wire = Some(NettyWire(ctx.channel().remoteAddress(), isServer, promise.future, ctx))
		super.channelActive(ctx)

		// 接続完了を通知
		onWireCreate(wire.get)
	}

	/*
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
	*/

	override def channelInactive(ctx:ChannelHandlerContext):Unit = {
		logger.trace(s"$sym[$id]: channelInactive($ctx)")
		close()
		super.channelInactive(ctx)
	}
	/*
	override def channelClosed(ctx:ChannelHandlerContext, e:ChannelStateEvent):Unit = {
		close()
		super.channelClosed(ctx, e)
	}
	*/

	private val authed = new Once()
	override def channelRead0(ctx:ChannelHandlerContext, msg:Message):Unit = {
		logger.trace(s"$sym[$id]: channelRead0($ctx,$msg)")
		authed {
			// 接続相手の SSL 情報を出力
			Option(ctx.channel().pipeline().get(classOf[SslHandler])).foreach { s =>
				val session = s.engine().getSession
				if(logger.isTraceEnabled){
					logger.trace(s"$sym: CipherSuite   : ${session.getCipherSuite}")
					logger.trace(s"$sym: LocalPrincipal: ${session.getLocalPrincipal}")
					logger.trace(s"$sym: PeerHost      : ${session.getPeerHost}")
					logger.trace(s"$sym: PeerPort      : ${session.getPeerPort}")
					logger.trace(s"$sym: PeerPrincipal : ${session.getPeerPrincipal}")
				}
			}
		}

		// メッセージを通知
		wire.foreach{ _._receive(msg) }

		// super.channelRead0(ctx, msg)
	}
	/*
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
	*/

	override def exceptionCaught(ctx:ChannelHandlerContext, cause:Throwable):Unit = {
		logger.debug(s"$sym[$id]: exception caught", cause)
		close()
	}
	/*
	override def exceptionCaught(ctx:ChannelHandlerContext, e:ExceptionEvent){
		logger.debug("exception caught", e.getCause)
		e.getChannel.getAttachment match {
			case s:Session => s.close()
			case _ => None
		}
	}
	*/

	private[this] def close():Unit = {
		logger.trace(s"$sym[$id]: close()")
		wire.foreach{ _.close() }
		wire = None
	}

}

private[netty] object WireConnect {
	private[WireConnect] val logger = LoggerFactory.getLogger(classOf[WireConnect])

	private[WireConnect] val seq = new AtomicInteger(0)
}
