/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.netty

import java.net.{InetSocketAddress, SocketAddress}
import scala.concurrent.Future
import javax.net.ssl.SSLSession
import io.netty.channel.ChannelHandlerContext
import com.kazzla.asterisk.{Message, Wire}
import java.io.IOException
import org.slf4j.LoggerFactory

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// NettyWire
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * Netty を使用した Wire 実装です。
 *
 * @author Takami Torao
 */
private[netty] case class NettyWire(address:SocketAddress, override val isServer:Boolean, override val tls:Future[Option[SSLSession]], context:ChannelHandlerContext) extends Wire {

	private[this] lazy val sym = if(isServer) "S" else "C"

	override val peerName = address match {
		case i:InetSocketAddress =>
			s"${i.getAddress.getHostAddress}:${i.getPort}"
		case s => s.toString
	}

	def send(m:Message):Unit = if(! isClosed){
		NettyWire.logger.trace(s"$sym: send($m)")
		val ch = context.channel()
		val future = ch.write(m)
		ch.flush()
		future.sync()
		if(! future.isSuccess){
			if(future.cause() != null){
				throw future.cause()
			}
			throw new IOException(s"$sym: send operation failure: $m")
		}
	} else {
		throw new IOException(s"$sym: cannot send on closed channel: $m")
	}

	override def close():Unit = if(! isClosed){
		NettyWire.logger.trace(s"$sym: close()")
		context.channel().close()
		super.close()
	}

	private[netty] def _receive(msg:Message):Unit = {
		NettyWire.logger.trace(s"$sym: _receive($msg)")
		receive(msg)
	}

}

private[netty] object NettyWire {
	val logger = LoggerFactory.getLogger(classOf[NettyWire])
}

