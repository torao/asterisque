package io.asterisque.core.wire.netty

import java.net.{InetSocketAddress, URI}
import java.util.concurrent.TimeUnit

import io.asterisque.core.ProtocolException
import io.asterisque.Scala._
import io.asterisque.test._
import io.asterisque.core.msg.{Message, Open}
import io.asterisque.core.wire.netty.WebSocket.Servant
import io.asterisque.core.wire.{Bridge, BridgeSpec, Wire}
import io.netty.buffer.Unpooled
import io.netty.channel.{Channel, ChannelHandlerContext}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.handler.codec.http.websocketx.{BinaryWebSocketFrame, TextWebSocketFrame, WebSocketFrame}
import javax.annotation.Nonnull
import org.slf4j.LoggerFactory
import org.specs2.specification.core.SpecStructure

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.Duration

class NettyBridgeWebSocketSpec extends BridgeSpec {

  override def is:SpecStructure = super.is append
    s2"""
        |Echo server and client work. $echoServerAndClient
        |communicate simple echo messaging. $echoCommunication
        |WebSocket peer returns a frame that isn't asterisque message. $messageCannotRestore
        |peer sends unsupported WebSocket frame. $peerSendUnsupportedWSFrame
"""

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
    Await.result(server.bind(new InetSocketAddress(0), ch => new EchoServant(ch)).asScala.map { ch =>
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

        server.destroy()

        (wire.isPrimary === false) and (wire.session().isPresent === false) and (actual === expected)
      }
    }.flatten, Duration.Inf)
  }

  private[this] def echoCommunication = {
    val bridge = newBridge()

    class OneTimeEchoMessageQueue(@Nonnull id:String, pipeId:Int) extends QueuedMessageQueue[Object] {

      override def offer(msg:Message):Unit = if(msg.pipeId != pipeId) {
        super.send(msg)
        logger.info(s"echoback send from $id")
      } else {
        super.offer(msg)
        logger.info(s"echoback received at $id")
      }
    }

    val serverPlug = new OneTimeEchoMessageQueue("server", 0)
    val clientPlug = new OneTimeEchoMessageQueue("client", -1)

    val server = bridge.newServer(new URI("ws://localhost:0"), "echo", null,
      { f => f.asScala.foreach(_.bound(serverPlug)) }
    ).join()

    val port = server.bindAddress().asInstanceOf[InetSocketAddress].getPort
    val wire = bridge.newWire(new URI(s"ws://localhost:$port"), "echo").join()
    wire.bound(clientPlug)

    val serverExpected = new Open(0, 1, 3, Array[Object](Integer.valueOf(100), "foo"))
    serverPlug.send(serverExpected)
    val serverActual = serverPlug.receive()

    val clientExpected = new Open(-1, -2, -3, Array[Object](Integer.valueOf(200), "bar"))
    clientPlug.send(clientExpected)
    val clientActual = clientPlug.receive()

    wire.local()
    wire.remote()
    wire.session()

    wire.close()
    server.close()

    (serverActual === serverExpected) and (clientActual === clientExpected) and
      (server.node() === serverNode) and (wire.node() === clientNode)
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
    val plug = new QueuedMessageQueue[String]() {
      override def onException(wire:Wire[String], ex:Throwable):Unit = {
        promise.success(ex)
      }
    }

    val eventLoop = new NioEventLoopGroup()
    val server = new WebSocket.Server(eventLoop, "errorcase", "/ws")
    val future = server.bind(new InetSocketAddress(0), _ => servant).asScala.flatMap { ch =>
      val port = ch.localAddress().asInstanceOf[InetSocketAddress].getPort
      val bridge = newBridge[String]()
      bridge.newWire("foo", URI.create(s"ws://localhost:$port/ws"), "errorcase").asScala.map { wire =>
        wire.bound(plug)
        wire
      }
    }
    val wire = Await.result(future, Duration.Inf)
    val ex = Await.result(promise.future, Duration.Inf)
    server.destroy()
    wire.close()

    expectedType.isAssignableFrom(ex.getClass) must beTrue
  }

}
