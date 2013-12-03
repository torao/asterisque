/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/

import java.net.{InetSocketAddress, SocketAddress}
import javax.net.ssl.SSLContext
import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.channel._
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.handler.codec.http.{HttpMethod, HttpVersion, DefaultHttpRequest, HttpClientCodec}
import org.jboss.netty.handler.ssl.SslHandler
import scala.None

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
		val pipelineFactory = new ChannelPipelineFactory {
			def getPipeline:ChannelPipeline = {
				val pipeline = Channels.pipeline()
				val engine = context.createSSLEngine()
				engine.setUseClientMode(true)
				pipeline.addLast("tls", new SslHandler(engine))
				pipeline.addLast("http", new HttpClientCodec())
				pipeline.addLast("app", new SimpleChannelHandler {
					override def channelConnected(ctx:ChannelHandlerContext, e:ChannelStateEvent):Unit = {
						System.out.println(engine.getEnabledCipherSuites.mkString("[",",","]"))
						System.out.println(engine.getEnabledProtocols.mkString("[",",","]"))
						pipeline.get("tls").asInstanceOf[SslHandler].handshake().addListener(new ChannelFutureListener {
							def operationComplete(future:ChannelFuture) = {
								val session = engine.getSession
								System.out.println(session.getCipherSuite)
								System.out.println(session.getValueNames.mkString("[",",","]"))
								System.out.println(session.getPeerCertificates.mkString("[",",","]"))
								System.out.println(session.getPeerCertificateChain.mkString("[",",","]"))
							}
						})
						val req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")
						req.setHeader("Connection", "close")
						req.setHeader("Host", "www.google.com")
						val ch = ctx.getChannel
						val event = new DownstreamMessageEvent(ch, Channels.future(ch), req, ch.getRemoteAddress)
						ctx.sendDownstream(event)
					}
					override def messageReceived(ctx:ChannelHandlerContext, e:MessageEvent):Unit = {
						val msg = e.getMessage
						System.out.println(msg)
					}
				})
				pipeline
			}
		}
		val client = new ClientBootstrap(new NioClientSocketChannelFactory())
		client.setPipelineFactory(pipelineFactory)
		client.connect(new InetSocketAddress("www.google.com", 443))
	}
}
