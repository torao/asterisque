/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/

import io.netty.bootstrap.Bootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.{SimpleChannelInboundHandler, ChannelOption, ChannelHandlerContext, ChannelInitializer}
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http._
import io.netty.handler.ssl.SslHandler
import io.netty.util.concurrent.{Future, GenericFutureListener}
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// SSLTest
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * 正規のサーバ証明書を使用しているサイトとの Netty + TLS を用いた通信のテスト。
 * @author Takami Torao
 */
object SSLTest {
	val context = SSLContext.getDefault
	def main(args:Array[String]):Unit = {
		val pipelineFactory = new ChannelInitializer[SocketChannel] {
			override def initChannel(ch:SocketChannel) = {
				val pipeline = ch.pipeline()
				val engine = context.createSSLEngine()
				engine.setUseClientMode(true)
				pipeline.addLast("tls", new SslHandler(engine))
				pipeline.addLast("http", new HttpClientCodec())
				pipeline.addLast("app", new SimpleChannelInboundHandler[DefaultHttpMessage]() {
					override def channelActive(ctx:ChannelHandlerContext):Unit = {
						System.out.println(engine.getEnabledCipherSuites.mkString("[",",","]"))
						System.out.println(engine.getEnabledProtocols.mkString("[",",","]"))
						pipeline.get("tls").asInstanceOf[SslHandler].handshakeFuture().addListener(new GenericFutureListener[Future[Any]] {
							def operationComplete(future:Future[Any]):Unit = {
								val session = engine.getSession
								System.out.println(session.getCipherSuite)
								System.out.println(session.getValueNames.mkString("[",",","]"))
								System.out.println(session.getPeerCertificates.mkString("[",",","]"))
								System.out.println(session.getPeerCertificateChain.mkString("[",",","]"))
							}
						})
						val req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")
						req.headers().set("Connection", "close")
						req.headers().set("Host", "www.google.com")
						ctx.channel().write(req)
					}
					override def channelRead0(ctx:ChannelHandlerContext, res:DefaultHttpMessage):Unit = {
						System.out.println(res)
					}
				})
			}
		}
		val group = new NioEventLoopGroup()
		val client = new Bootstrap()
		client
			.group(group)
			.channel(classOf[NioSocketChannel])
			.remoteAddress(new InetSocketAddress("www.google.com", 443))
			.option(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)    // TODO 確認
			.handler(pipelineFactory)
		client.connect()
	}
}
