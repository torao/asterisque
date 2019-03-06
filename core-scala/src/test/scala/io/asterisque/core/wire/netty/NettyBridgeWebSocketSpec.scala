package io.asterisque.core.wire.netty

import java.net.{InetSocketAddress, URI}
import java.util.concurrent.TimeUnit

import io.asterisque.Scala._
import io.asterisque.core.msg.Open
import io.asterisque.core.wire.netty.WebSocket.Servant
import io.asterisque.core.wire.{Bridge, BridgeSpec, MessageQueue, Wire}
import io.asterisque.test._
import io.asterisque.utils.Debug
import io.asterisque.wire.ProtocolException
import io.netty.buffer.Unpooled
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.{Channel, ChannelHandlerContext}
import io.netty.handler.codec.http.websocketx.{BinaryWebSocketFrame, TextWebSocketFrame, WebSocketFrame}
import javax.annotation.Nonnull
import org.slf4j.LoggerFactory
import org.specs2.matcher.MatchResult
import org.specs2.specification.core.SpecStructure

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}

class NettyBridgeWebSocketSpec extends BridgeSpec {

  override def is:SpecStructure = super.is append
    s2"""
        |Echo server and client work. $echoServerAndClient
        |communicate simple echo messaging. $echoCommunication
        |WebSocket peer returns a frame that isn't asterisque message. ${if(false) skipped else messageCannotRestore}
        |peer sends unsupported WebSocket frame. $peerSendUnsupportedWSFrame
""".stripMargin

  private[this] val logger = LoggerFactory.getLogger(getClass)

  override def newBridge():Bridge = new NettyBridge()

  /**
    * WebSocket によるエコーサーバとクライアント。
    */
  private[this] def echoServerAndClient = {
    val subprotocol = randomString(983, 16)
    val path = "/ws"

    class EchoServant(channel:Channel) extends Servant {
      override def wsReady(ctx:ChannelHandlerContext):Unit = {
        logger.debug(s"EchoServant.wsReady($ctx)")
      }

      override def wsFrameReceived(ctx:ChannelHandlerContext, msg:WebSocketFrame):Unit = {
        logger.debug(s"EchoServant.wsClosed($ctx, $msg)")
        channel.writeAndFlush(msg.retain())
      }

      override def wsClosed(ctx:ChannelHandlerContext):Unit = {
        logger.debug(s"EchoServant.wsClosed($ctx)")
        channel.close()
      }

      override def wsCaughtException(ctx:ChannelHandlerContext, ex:Throwable):Unit = {
        logger.error(s"EchoServant.wsClosed($ctx, $ex)", ex)
      }
    }

    // Echo サーバの開始
    val serverEventLoop = new NioEventLoopGroup()
    val server = new WebSocket.Server(serverEventLoop, subprotocol, path)
    val result = Await.result(server.bind(new InetSocketAddress(0), ch => new EchoServant(ch)).asScala.map { ch =>
      val port = ch.localAddress().asInstanceOf[InetSocketAddress].getPort

      // Echo クライアントから接続してメッセージ送信
      val bridge = newBridge()
      val uri = s"ws://localhost:$port$path"
      bridge.newWire(new URI(uri), subprotocol).asScala.map { wire =>
        logger.debug("wire creation completed")

        // short pipeId, byte priority, short functionId, @Nonnull Object[] params
        val expected = new Open(0.toShort, 0.toByte, 0.toShort, Array[Object]("foo", "bar"))
        wire.outbound.offer(expected)
        val actual = wire.inbound.poll(Int.MaxValue, TimeUnit.SECONDS)

        bridge.close()

        (wire.isPrimary === false) and (wire.session().isPresent === false) and (actual === expected)
      }
    }.flatten, Duration.Inf)

    server.destroy()
    serverEventLoop.shutdownGracefully()

    result
  }

  private[this] def echoCommunication:MatchResult[String] = {
    val bridge = newBridge()

    // (message:String, ttl:Int) パラメータの Open メッセージを受け付ける Echo Servant
    def initTTLEchoServant(name:String, wire:Wire):Future[String] = {
      val promise = Promise[String]()
      wire.inbound.addListener(new MessageQueue.Listener {
        override def messagePollable(messageQueue:MessageQueue, pollable:Boolean):Unit = while(pollable) {
          Option(wire.inbound.poll()) match {
            case Some(open:Open) =>
              open.params match {
                case Array(message:String, ttl:Integer) =>
                  if(ttl <= 1) {
                    logger.info(s"$name: terminate: $message")
                    promise.success(message)
                  } else {
                    val params = Array[AnyRef](message, Integer.valueOf(ttl.intValue() - 1))
                    val echoback = new Open(open.priority, open.priority, open.functionId, params)
                    logger.info(s"$name: echoback: $open -> $echoback")
                    wire.outbound.offer(echoback)
                  }
                case _ =>
                  logger.error(s"unexpected open parameter: ${Debug.toString(open.params)}")
              }
            case None => return
          }
        }
      })
      promise.future
    }

    val serverPromise = Promise[String]()
    val server = bridge.newServer(new URI("ws://localhost:0"), "echo", null,
      { f =>
        f.asScala.foreach(wire => {
          val future = initTTLEchoServant("SERVER", wire)

          wire.outbound.offer(new Open(0, 1, 3, Array[Object]("foo", Integer.valueOf(10))))

          serverPromise.completeWith(future)
        })
      }
    ).join()

    val port = server.bindAddress().asInstanceOf[InetSocketAddress].getPort
    val wire = bridge.newWire(new URI(s"ws://localhost:$port"), "echo").join()
    val future = initTTLEchoServant("CLIENT", wire)

    wire.outbound.offer(new Open(-1, -2, -3, Array[Object]("bar", Integer.valueOf(10))))

    val serverResult = Await.result(serverPromise.future, Duration.Inf) === "foo"
    val clientResult = Await.result(future, Duration.Inf) === "bar"

    wire.local()
    wire.remote()
    wire.session()

    wire.close()
    server.close()

    bridge.close()

    clientResult and serverResult
  }

  private[this] def messageCannotRestore = exceptionCallback(new Servant {
    override def wsReady(ctx:ChannelHandlerContext):Unit = {
      ctx.channel().writeAndFlush(new BinaryWebSocketFrame(Unpooled.EMPTY_BUFFER))
    }

    override def wsFrameReceived(ctx:ChannelHandlerContext, msg:WebSocketFrame):Unit = ()

    override def wsClosed(ctx:ChannelHandlerContext):Unit = ()

    override def wsCaughtException(ctx:ChannelHandlerContext, ex:Throwable):Unit = ()
  }, classOf[ProtocolException])

  private[this] def peerSendUnsupportedWSFrame = exceptionCallback(new Servant {
    override def wsReady(ctx:ChannelHandlerContext):Unit = {
      ctx.channel().writeAndFlush(new TextWebSocketFrame("hello, world"))
    }

    override def wsFrameReceived(ctx:ChannelHandlerContext, msg:WebSocketFrame):Unit = ()

    override def wsClosed(ctx:ChannelHandlerContext):Unit = ()

    override def wsCaughtException(ctx:ChannelHandlerContext, ex:Throwable):Unit = ()
  }, classOf[ProtocolException])

  private[this] def exceptionCallback(@Nonnull servant:Servant, @Nonnull expectedType:Class[_ <: Throwable]) = {
    val promise = Promise[Throwable]()
    val listener = new Wire.Listener {
      override def wireClosed(wire:Wire):Unit = {}

      override def wireError(wire:Wire, ex:Throwable):Unit = promise.success(ex)
    }

    val eventLoop = new NioEventLoopGroup()
    val server = new WebSocket.Server(eventLoop, "errorcase", "/ws")
    val future = server.bind(new InetSocketAddress(0), _ => servant).asScala.flatMap { ch =>
      val port = ch.localAddress().asInstanceOf[InetSocketAddress].getPort
      val bridge = newBridge()
      bridge.newWire(URI.create(s"ws://localhost:$port/ws"), "errorcase").asScala.map { wire =>
        wire.addListener(listener)
        wire
      }
    }
    val wire = Await.result(future, Duration(1, TimeUnit.MINUTES))
    val ex = Await.result(promise.future, Duration(1, TimeUnit.MINUTES))
    server.destroy()
    wire.close()
    eventLoop.shutdownGracefully()

    expectedType.isAssignableFrom(ex.getClass) must beTrue
  }

}
