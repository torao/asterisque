/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.netty

import java.util

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.{ChannelHandlerContext, ChannelInitializer, ChannelOption}
import io.netty.channel.nio.{NioEventLoopGroup, NioEventLoop}
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.ByteToMessageDecoder

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// NettySample
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
object NettySample {
	def main(args:Array[String]):Unit = {
		val master = new NioEventLoopGroup()
		val worker = new NioEventLoopGroup()

		val port = 7263
		val server = new ServerBootstrap()
			.group(master, worker)
			.channel(classOf[NioServerSocketChannel])
			.localAddress(port)
			.option(ChannelOption.SO_BACKLOG, java.lang.Integer.valueOf(100))
			.childOption(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
			.childHandler(new ChannelInitializer[SocketChannel]() {
				override def initChannel(ch:SocketChannel):Unit = {
					val pipeline = ch.pipeline()
					pipeline.addLast("c", new ByteToMessageDecoder {
						override def decode(ctx:ChannelHandlerContext, in:ByteBuf, out:util.List[AnyRef]):Unit = {

						}
					})
				}
			})
		server.bind(port)
	}
}
