package io.asterisque.core.wire.netty

import java.net.{HttpURLConnection, _}
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicReference
import java.util.{Base64, Random}

import io.asterisque.Scala._
import io.asterisque.core.wire.netty.WebSocket.Servant
import io.netty.buffer.Unpooled
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.{Channel, ChannelHandlerContext}
import io.netty.handler.codec.http.websocketx.{BinaryWebSocketFrame, CloseWebSocketFrame, TextWebSocketFrame, WebSocketFrame}
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.util
import javax.annotation.Nonnull
import org.slf4j.LoggerFactory
import org.specs2.Specification
import org.specs2.matcher.MatchResult

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}

class WebSocketSpec extends Specification {
  def is =
    s2"""
WebSocket should:
echo server $echoServer
callback error when connection failure. $clientConnectionFailure
callback error when WebSocket handshake failed. $webSocketHandshakeFail
WebSocket.Server responds 401 Forbidden when client requests a path other than WebSocket. $forbiddenIfHttpRequestOtherThanWS
callback exception when listener raise exception. $callbackWhenListenerRaiseException
callback exception when server fail to bind. $serverFailToBind
"""

  private[this] val logger = LoggerFactory.getLogger(getClass)

  private[this] def echoServer = {
    val serverListener:WebSocket.Server.Listener = WebSocket.Server.EmptyListener
    val serverAcceptListener:Servant = new Adapter() {
      override def read(@Nonnull ctx:ChannelHandlerContext, msg:WebSocketFrame):Unit = msg match {
        case t:TextWebSocketFrame =>
          logger.info("SERVER<< {}", t.text())
          ctx.channel.writeAndFlush(msg.retain)
          logger.info("SERVER>> {}", t.text())
      }
    }

    val port = 12345
    val subprotocol = "echo"

    val cert = new SelfSignedCertificate()
    cert.certificate.deleteOnExit()
    cert.privateKey.deleteOnExit()
    val serverSslContext = SslContextBuilder.forServer(cert.certificate, cert.privateKey).build()
    val serverEventLoop = new NioEventLoopGroup()
    val endpoint = new WebSocket.Server(serverEventLoop, subprotocol, "/ws", serverListener, serverSslContext)
    val future = endpoint.bind(new InetSocketAddress(port), _ => serverAcceptListener)
    Runtime.getRuntime.addShutdownHook(new Thread(() => endpoint.destroy()))

    val clientSslContext = SslContextBuilder.forClient.trustManager(cert.cert).build
    val clientEventLoop = new NioEventLoopGroup
    val results = mutable.Buffer[MatchResult[_]]()
    (0 to 10).map { i =>
      val clientListener:Servant = new Adapter() {
        private[this] var expected = randomString
        private[this] var remaining = 10

        override def ready(@Nonnull ctx:ChannelHandlerContext):Unit = {
          logger.info("CLIENT[{}]>> {}", i, expected)
          ctx.channel.writeAndFlush(new TextWebSocketFrame(expected))
          remaining -= 1
        }

        override def read(@Nonnull ctx:ChannelHandlerContext, @Nonnull msg:WebSocketFrame):Unit = msg match {
          case t:TextWebSocketFrame =>
            logger.info("CLIENT[{}]<< {}", i, t.text())
            results.append(expected === t.text())
            remaining -= 1
            if(remaining <= 0) {
              ctx.channel.writeAndFlush(new CloseWebSocketFrame()).addListener((future:util.concurrent.Future[_ >: Void]) => ctx.channel().close())
              logger.info("CLIENT[{}]<< CLOSE", i)
            } else {
              expected = randomString
              ctx.channel.writeAndFlush(new TextWebSocketFrame(expected))
            }
        }

        override def closing(@Nonnull ctx:ChannelHandlerContext):Unit = {
          logger.info("CLIENT[{}]: CLOSING", i)
        }
      }
      val client = new WebSocket.Client(clientEventLoop, subprotocol, clientListener, clientSslContext)
      val clientFuture = client.connect(URI.create("wss://localhost:" + port + "/ws"))
      Runtime.getRuntime.addShutdownHook(new Thread(() => client.destroy()))
      clientFuture
    }.foreach(_.join().closeFuture().syncUninterruptibly())

    endpoint.destroy()
    future.join().closeFuture().syncUninterruptibly()

    results.reduceLeft(_ and _)
  }

  private[this] def clientConnectionFailure = {
    val threads = new NioEventLoopGroup()
    val client = new WebSocket.Client(threads, "disconnect", new Adapter(), null)

    client.connect(URI.create("ws://localhost:0/wsss")).join() must throwA[CompletionException].like {
      case ex =>
        rootCause(ex).isInstanceOf[BindException] must beTrue
    }
  }

  private[this] def webSocketHandshakeFail = {

    // 1 バイトを読み込んで切断するサーバ
    val portFuture = Promise[Int]()
    val future:Future[Unit] = Future {
      val server = new ServerSocket(0)
      portFuture.success(server.getLocalPort)
      val socket = server.accept()
      server.close()
      logger.debug("connection accepted")
      val in = socket.getInputStream
      val ch = in.read()
      logger.debug(f"read 1 byte: 0x$ch%02X")
      socket.close()
    }
    val port = Await.result(portFuture.future, Duration.Inf)

    // WebSocket ハンドシェイクに失敗する例外が発生するか
    val _exception = new AtomicReference[Throwable]()
    val pool = new NioEventLoopGroup()
    val client = new WebSocket.Client(pool, "disconnect", new Adapter() {
      override def ready(ctx:ChannelHandlerContext):Unit = {
        ctx.channel().writeAndFlush(new BinaryWebSocketFrame(Unpooled.buffer(10)))
      }

      override def exception(ctx:ChannelHandlerContext, ex:Throwable):Unit = {
        logger.debug(s"exception($ctx, $ex)")
        _exception.set(ex)
      }
    }, null)
    client.connect(URI.create(s"ws://localhost:$port")).join().closeFuture().awaitUninterruptibly()
    Await.ready(future, Duration.Inf)

    _exception.get().isInstanceOf[WebSocket.HandshakeException] must beTrue
  }

  private[this] def forbiddenIfHttpRequestOtherThanWS = {
    val listener = WebSocket.Server.EmptyListener
    val servant = new Servant {
      override def ready(ctx:ChannelHandlerContext):Unit = ()

      override def read(ctx:ChannelHandlerContext, msg:WebSocketFrame):Unit = ()

      override def closing(ctx:ChannelHandlerContext):Unit = ()

      override def exception(ctx:ChannelHandlerContext, ex:Throwable):Unit = ()
    }
    val eventLoop = new NioEventLoopGroup()
    val server = new WebSocket.Server(eventLoop, "http", "/ws", listener, null)
    val future = server.bind(new InetSocketAddress(0), _ => servant).asScala.map { ch =>
      val port = ch.localAddress().asInstanceOf[InetSocketAddress].getPort
      val url = new URL(s"http://localhost:$port/")
      val con = url.openConnection().asInstanceOf[HttpURLConnection]
      con.getResponseCode
      val in = con.getErrorStream
      val content = Iterator.continually(in.read()).takeWhile(_ >= 0).map(_.toByte).toArray
      in.close()
      val message = new String(content, StandardCharsets.UTF_8)
      logger.info(
        s"""HTTP WebSocket Response
           |${con.getHeaderField(null)}
           |${
          con.getHeaderFields.asScala.toSeq
            .filter(_._1 != null)
            .flatMap(e => e._2.asScala.map(value => (e._1, value))).map(e => s"${e._1}: ${e._2}")
            .mkString("\n")
        }\n
           |$message""".stripMargin)
      con
    }
    val con = Await.result(future, Duration.Inf)
    server.destroy()
    eventLoop.shutdownGracefully()
    con.getResponseCode === HttpURLConnection.HTTP_FORBIDDEN
  }

  private[this] def callbackWhenListenerRaiseException = {
    class TestException extends Exception
    val servant1 = new Servant {
      override def ready(ctx:ChannelHandlerContext):Unit = {
        logger.info("SERVER: ready callback")
        val frame = Unpooled.wrappedBuffer((0 to 0xFF).map(_.toByte).toArray)
        ctx.channel().writeAndFlush(new BinaryWebSocketFrame(frame))
      }

      override def read(ctx:ChannelHandlerContext, msg:WebSocketFrame):Unit = {
        logger.info("SERVER: read callback")
      }

      override def closing(ctx:ChannelHandlerContext):Unit = {
        logger.info("SERVER: closing callback")
      }

      override def exception(ctx:ChannelHandlerContext, ex:Throwable):Unit = {
        logger.info(s"SERVER: exception callback: $ex")
      }
    }
    val promise = Promise[Throwable]()
    val servant2 = new Servant {
      override def ready(ctx:ChannelHandlerContext):Unit = {
        logger.info("CLIENT: ready callback")
      }

      override def read(ctx:ChannelHandlerContext, msg:WebSocketFrame):Unit = {
        logger.info("CLIENT: read callback")
        throw new TestException
      }

      override def closing(ctx:ChannelHandlerContext):Unit = {
        logger.info("CLIENT: closing callback")
      }

      override def exception(ctx:ChannelHandlerContext, ex:Throwable):Unit = {
        logger.info(s"CLIENT: exception callback: $ex")
        promise.success(ex)
      }
    }
    val eventLoop = new NioEventLoopGroup()
    val server = new WebSocket.Server(eventLoop, "exception", "/ws")
    val future = server.bind(new InetSocketAddress(0), _ => servant1).asScala.flatMap { ch =>
      val port = ch.localAddress().asInstanceOf[InetSocketAddress].getPort
      val client = new WebSocket.Client(eventLoop, "exception", servant2, null)
      client.connect(new URI(s"ws://localhost:$port/ws")).asScala.map(_ => client)
    }
    val exception = Await.result(promise.future, Duration.Inf)
    Await.result(future, Duration.Inf).destroy()
    server.destroy()
    eventLoop.shutdownGracefully()

    exception.isInstanceOf[TestException] must beTrue
  }

  private[this] def serverFailToBind = {
    val eventLoop = new NioEventLoopGroup()
    val server = new WebSocket.Server(eventLoop, "bindfail", "/ws")
    val future = server.bind(new InetSocketAddress("255.255.255.255", 0), _ => null).asScala
    try {
      Await.result(future, Duration.Inf) must throwA[CompletionException].like {
        case ex => rootCause(ex).getClass === classOf[BindException]
      }
    } finally {
      server.destroy()
      eventLoop.shutdownGracefully()
    }
  }

  private[this] def rootCause(@Nonnull ex:Throwable):Throwable = if(ex.getCause != null) rootCause(ex.getCause) else ex

  private[this] val random = new Random()

  private[this] def randomString:String = {
    val binary = random.ints(16).toArray.map(_.toByte)
    Base64.getEncoder.encodeToString(binary)
  }

  class Adapter extends Servant with WebSocket.Server.Listener {
    override def ready(@Nonnull ctx:ChannelHandlerContext):Unit = {
    }

    override def read(@Nonnull ctx:ChannelHandlerContext, msg:WebSocketFrame):Unit = {
    }

    override def closing(@Nonnull ctx:ChannelHandlerContext):Unit = {
    }

    override def exception(@Nonnull ctx:ChannelHandlerContext, @Nonnull ex:Throwable):Unit = {
    }

    override def ready(@Nonnull ch:Channel):Unit = {
    }
  }

}
