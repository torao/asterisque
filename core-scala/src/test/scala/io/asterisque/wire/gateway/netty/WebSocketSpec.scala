package io.asterisque.wire.gateway.netty

import java.net.{BindException, InetSocketAddress, URI}
import java.util.concurrent.TimeUnit

import io.asterisque.test._
import io.asterisque.wire.gateway.netty.WebSocket.{Servant, Server}
import io.netty.buffer.Unpooled
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.{Channel, ChannelHandlerContext}
import io.netty.handler.codec.http.websocketx.{BinaryWebSocketFrame, TextWebSocketFrame, WebSocketFrame}
import io.netty.handler.ssl._
import org.slf4j.LoggerFactory
import org.specs2.Specification

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise}

class WebSocketSpec extends Specification {
  def is =
    s2"""
The server and client should connect and communicate with locally. $simpleLocalConnection
The server accept with SSL and client connect without SSL. $serverSSLHandshakeFailure
The server callback to listener and throw exception when it fails to bind. $serverBindFailure
The client connect to undefined URI path. $clientRequestsUndefinedURIPath
The client callback to listener and throw exception when it fails to connect. $clientConnectionFailure
SSL context is not specified but wss: protocol specified on server. $sslContextIsNotSpecified
It throws exception if uri schema is not for WebSocket. $specifyNotWSSSchemeURI
"""

  private[this] val logger = LoggerFactory.getLogger(classOf[WebSocketSpec])

  private[this] def simpleLocalConnection = Seq("ws", "wss").map { scheme =>
    val group = new NioEventLoopGroup()
    val subProtocol = "unit-test"

    val (privateKey, certificate) = NODE_CERTS.head
    val serverChallenge = randomASCII(238457, 16)
    val serverSSL = SslContextBuilder
      .forServer(privateKey, certificate) // 自己署名証明書を使用する
      .build()
    val serverURI = new URI(s"$scheme://localhost/unit-test")
    val server = new WebSocket.Server(group, subProtocol, NOOP_LISTENER, serverSSL)
    val serverResult = Promise[String]()
    val serverChannel = Await.result(server.bind(serverURI, { _ =>
      new TestServant(serverChallenge, serverResult)
    }), SEC30)
    val port = serverChannel.localAddress().asInstanceOf[InetSocketAddress].getPort

    val clientChallenge = randomASCII(348793, 16)
    val clientSSL = SslContextBuilder
      .forClient()
      .trustManager(certificate) // サーバが使用する自己署名証明書を信頼済みとする
      .build()
    val clientURI = new URI(s"$scheme://localhost:$port/unit-test")
    val client = new WebSocket.Client(group, subProtocol, clientSSL)
    val clientResult = Promise[String]()
    val clientChannel = Await.result(client.connect(clientURI, { _ =>
      new TestServant(clientChallenge, clientResult)
    }), SEC30)

    clientChannel.closeFuture().await()
    server.destroy()
    client.destroy()
    group.shutdownGracefully()
    (Await.result(serverResult.future, SEC30) === serverChallenge) and
      (Await.result(clientResult.future, SEC30) === clientChallenge)
  }.reduceLeft(_ and _)

  private[this] def serverBindFailure = {
    val group = new NioEventLoopGroup()
    val subProtocol = "unit-test"
    val serverURI = new URI("ws://255.255.255.255/unit-test")
    var result:Option[Throwable] = None
    val server = new WebSocket.Server(group, subProtocol, new Server.Listener {
      override def wsServerCaughtException(ctx:ChannelHandlerContext, ex:Throwable):Unit = {
        super.wsServerCaughtException(ctx, ex)
        result = Some(ex)
      }
    }, null)
    (Await.result(server.bind(serverURI, { _ => NOOP }), SEC30) must throwA[BindException]) and
      result.exists(_.isInstanceOf[BindException])
  }

  private[this] def serverSSLHandshakeFailure = {
    val group = new NioEventLoopGroup()
    val subProtocol = "unit-test"

    val (privateKey, certificate) = NODE_CERTS.head
    val serverChallenge = randomASCII(238457, 16)
    val serverSSL = SslContextBuilder
      .forServer(privateKey, certificate) // 自己署名証明書を使用する
      .build()
    val serverURI = new URI(s"wss://localhost/unit-test")
    val server = new WebSocket.Server(group, subProtocol, NOOP_LISTENER, serverSSL)
    val serverResult = Promise[String]()
    val serverChannel = Await.result(server.bind(serverURI, { _ =>
      new TestServant(serverChallenge, serverResult)
    }), SEC30)
    val port = serverChannel.localAddress().asInstanceOf[InetSocketAddress].getPort

    val clientChallenge = randomASCII(348793, 16)
    val clientURI = new URI(s"ws://localhost:$port/unit-test")
    val client = new WebSocket.Client(group, subProtocol, null)
    val clientResult = Promise[String]()
    val clientChannel = Await.result(client.connect(clientURI, { _ =>
      new TestServant(clientChallenge, clientResult)
    }), SEC30)

    clientChannel.closeFuture().await()
    server.destroy()
    client.destroy()
    group.shutdownGracefully()
    (Await.result(serverResult.future, SEC30) must throwA[Throwable]) and
      (Await.result(clientResult.future, SEC30) must throwA[Throwable])
  }

  private[this] def clientRequestsUndefinedURIPath = {
    val group = new NioEventLoopGroup()
    val subProtocol = "unit-test"

    val serverURI = new URI(s"ws://localhost/unit-test")
    val server = new WebSocket.Server(group, subProtocol, NOOP_LISTENER, null)
    val serverChannel = Await.result(server.bind(serverURI, { _ => null }), SEC30)
    val port = serverChannel.localAddress().asInstanceOf[InetSocketAddress].getPort

    val clientURI = new URI(s"ws://localhost:$port/undefined")
    val client = new WebSocket.Client(group, subProtocol, null)
    val channel = Await.result(client.connect(clientURI, { _ => null }), SEC30)
    channel.closeFuture().await() // サーバ側から切断しても例外が発生しない?
    channel.isOpen must beFalse
  }

  private[this] def clientConnectionFailure = {
    val group = new NioEventLoopGroup()
    val subProtocol = "unit-test"

    val clientChallenge = randomASCII(348793, 16)
    val clientURI = new URI(s"ws://localhost:65535/unit-test")
    val client = new WebSocket.Client(group, subProtocol, null)
    val clientResult = Promise[String]()
    Await.result(client.connect(clientURI, { _ =>
      new TestServant(clientChallenge, clientResult)
    }), SEC30) must throwA[Throwable]
  }

  private[this] def sslContextIsNotSpecified = {
    val group = new NioEventLoopGroup()
    val subProtocol = "unit-test"
    val serverURI = new URI("wss://localhost/unit-test")
    val server = new WebSocket.Server(group, subProtocol, NOOP_LISTENER, null)
    server.bind(serverURI, { _ => NOOP }) must throwA[IllegalArgumentException]
  }

  private[this] def specifyNotWSSSchemeURI = {
    val group = new NioEventLoopGroup()
    val subProtocol = "unit-test"
    val serverURI = new URI("http://localhost/unit-test")
    val server = new WebSocket.Server(group, subProtocol, NOOP_LISTENER, null)
    server.bind(serverURI, { _ => NOOP }) must throwA[IllegalArgumentException]
  }

  private[this] val SEC30 = Duration.apply(30, TimeUnit.SECONDS)

  private[this] class TestServant(challenge:String, promise:Promise[String]) extends Servant {
    override def wsReady(ctx:ChannelHandlerContext):Unit = {
      logger.info(s"wsReady($ctx)")
      val text = challenge
      val msg = new TextWebSocketFrame(text)
      logger.info(s"SEND: $text")
      ctx.channel().writeAndFlush(msg)
    }

    override def wsFrameReceived(ctx:ChannelHandlerContext, msg:WebSocketFrame):Unit = {
      logger.info(s"wsFrameReceived($ctx, $msg)")
      msg match {
        case text:TextWebSocketFrame =>
          logger.info(s"RECEIVE AND SEND: ${text.text}")
          val bin = Unpooled.wrappedBuffer(text.text.getBytes())
          ctx.channel().writeAndFlush(new BinaryWebSocketFrame(bin))
        case binary:BinaryWebSocketFrame =>
          val length = binary.content().readableBytes()
          val bytes = new Array[Byte](length)
          binary.content().readBytes(bytes)
          val text = new String(bytes)
          logger.info(s"RECEIVE: $text")
          ctx.close()
          promise.success(text)
      }
    }

    override def wsClosed(ctx:ChannelHandlerContext):Unit = {
      logger.info(s"wsClosed($ctx)")
    }

    override def wsCaughtException(ctx:ChannelHandlerContext, ex:Throwable):Unit = {
      logger.error(s"wsCaughtException($ctx, $ex)", ex)
      promise.failure(ex)
    }
  }

  private[this] object NOOP extends Servant {
    override def wsReady(ctx:ChannelHandlerContext):Unit = {
      logger.debug(s"wsReady($ctx)")
    }

    override def wsFrameReceived(ctx:ChannelHandlerContext, msg:WebSocketFrame):Unit = {
      logger.debug(s"wsFrameReceived($ctx)")
    }

    override def wsClosed(ctx:ChannelHandlerContext):Unit = {
      logger.debug(s"wsClosed($ctx)")
    }

    override def wsCaughtException(ctx:ChannelHandlerContext, ex:Throwable):Unit = {
      logger.debug(s"wsCaughtException($ctx, $ex)")
    }
  }

  private[this] object NOOP_LISTENER extends Server.Listener {
    override def wsServerReady(ch:Channel):Unit = {
      logger.info(s"wsServerReady($ch)")
    }

    override def wsServerCaughtException(ctx:ChannelHandlerContext, ex:Throwable):Unit = {
      logger.error(s"wsServerCaughtException($ctx, $ex)", ex)
    }
  }

}
