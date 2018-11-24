package io.asterisque.core.wire

import java.net.{InetSocketAddress, URI}

import io.asterisque.Scala._
import io.asterisque.core.msg.{Message, Open}
import io.asterisque.core.wire.netty.WebSocket
import io.asterisque.core.wire.netty.WebSocket.Servant
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.handler.codec.http.websocketx.{BinaryWebSocketFrame, TextWebSocketFrame, WebSocketFrame}
import javax.annotation.Nonnull
import org.slf4j.LoggerFactory
import org.specs2.specification.core.SpecStructure

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise}

abstract class WebSocketBridgeSpec extends BridgeSpec {

  override def is:SpecStructure = super.is append
    s2"""
communicate simple echo messaging. $echoCommunication
WebSocket peer returns a frame that isn't asterisque message. $messageCannotRestore
peer sends unsupported WebSocket frame. $peerSendUnsupportedWSFrame
"""

  private[this] val logger = LoggerFactory.getLogger(getClass)

  private[this] def echoCommunication = {
    val bridge = newBridge[Object]()

    class OneTimeEchoPlug(@Nonnull id:String, pipeId:Int) extends QueuedPlug[Object] {

      override def consume(msg:Message):Unit = if(msg.pipeId != pipeId) {
        super.send(msg)
        logger.info(s"echoback send from $id")
      } else {
        super.consume(msg)
        logger.info(s"echoback received at $id")
      }
    }

    val serverPlug = new OneTimeEchoPlug("server", 0)
    val clientPlug = new OneTimeEchoPlug("client", -1)

    val serverNode = new Object()
    val server = bridge.newServer(
      serverNode, new URI("ws://localhost:0"), "echo", null,
      { f => f.asScala.foreach(_.bound(serverPlug)) }
    ).join()

    val port = server.bindAddress().asInstanceOf[InetSocketAddress].getPort
    val clientNode = new Object()
    val wire = bridge.newWire(clientNode, new URI(s"ws://localhost:$port"), "echo").join()
    wire.bound(clientPlug)

    val serverExpected = new Open(0, 1, 3, Array[Object](Integer.valueOf(100), "foo"))
    serverPlug.send(serverExpected)
    val serverActual = serverPlug.receive()

    val clientExpected = new Open(-1, -2, -3, Array[Object](Integer.valueOf(200), "bar"))
    clientPlug.send(clientExpected)
    val clientActual = clientPlug.receive()

    wire.close()
    server.close()

    (serverActual === serverExpected) and (clientActual === clientExpected) and
      (server.node() === serverNode) and (wire.node() === clientNode)
  }

  private[this] def messageCannotRestore = exceptionCallback(new Servant {
    override def ready(ctx:ChannelHandlerContext):Unit = {
      ctx.channel().writeAndFlush(new BinaryWebSocketFrame(Unpooled.EMPTY_BUFFER))
    }

    override def read(ctx:ChannelHandlerContext, msg:WebSocketFrame):Unit = ()

    override def closing(ctx:ChannelHandlerContext):Unit = ()

    override def exception(ctx:ChannelHandlerContext, ex:Throwable):Unit = ()
  }, classOf[ProtocolException])

  private[this] def peerSendUnsupportedWSFrame = exceptionCallback(new Servant {
    override def ready(ctx:ChannelHandlerContext):Unit = {
      ctx.channel().writeAndFlush(new TextWebSocketFrame("hello, world"))
    }

    override def read(ctx:ChannelHandlerContext, msg:WebSocketFrame):Unit = ()

    override def closing(ctx:ChannelHandlerContext):Unit = ()

    override def exception(ctx:ChannelHandlerContext, ex:Throwable):Unit = ()
  }, classOf[ProtocolException])

  private[this] def exceptionCallback(@Nonnull servant:Servant, @Nonnull expectedType:Class[_ <: Throwable]) = {
    val promise = Promise[Throwable]()
    val plug = new QueuedPlug[String]() {
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
