package io.asterisque.core.wire

import java.net.{InetSocketAddress, URI}
import java.util.concurrent.{ArrayBlockingQueue, CompletableFuture}

import io.asterisque.core.msg.{Message, Open}
import io.asterisque.core.wire.netty.WebSocket
import io.asterisque.core.wire.netty.WebSocket.Servant
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.{Channel, ChannelHandlerContext}
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import org.slf4j.LoggerFactory
import org.specs2.Specification

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}

abstract class BridgeSpec extends Specification {
  def is =
    s2"""
Bridge generates new wire $generateNewWire
"""

  private val logger = LoggerFactory.getLogger(getClass)

  private[this] def generateNewWire = {
    val subprotocol = randomString(983, 16)
    val path = "/ws"
    val node = randomString(23494, 64)

    class EchoService(channel:Channel) extends Servant {
      override def ready(ctx:ChannelHandlerContext):Unit = ()

      override def read(ctx:ChannelHandlerContext, msg:WebSocketFrame):Unit = {
        channel.writeAndFlush(msg.retain())
        ()
      }

      override def closing(ctx:ChannelHandlerContext):Unit = {
        logger.debug(s"closing($ctx)", ctx)
        channel.close()
        ()
      }

      override def exception(ctx:ChannelHandlerContext, ex:Throwable):Unit = {
        logger.error("", ex)
      }
    }

    // Echo サーバの開始
    val serverEventLoop = new NioEventLoopGroup()
    val endpoint = new WebSocket.Server(serverEventLoop, subprotocol, path)
    Await.result(endpoint.bind(new InetSocketAddress(0), ch => new EchoService(ch)).asScala.map { ch =>
      val port = ch.localAddress().asInstanceOf[InetSocketAddress].getPort

      // クライアントの送信
      val plug = new QueuedPlug[String]()
      val bridge = newBridge[String]()
      val uri = s"ws://localhost:$port$path"
      bridge.newWire(node, new URI(uri), subprotocol).asScala.map { wire =>
        logger.debug("wire creation completed")

        wire.bound(plug)
        logger.debug("plug bound")

        // short pipeId, byte priority, short functionId, @Nonnull Object[] params
        val expected = new Open(0.toShort, 0.toByte, 0.toShort, Array[Object]("foo", "bar"))
        plug.send(expected)
        val actual = plug.receive()

        (wire.node() === node) and (wire.isPrimary === false) and (wire.session().isPresent === false) and
          (actual === expected)
      }
    }.flatten, Duration.Inf)
  }

  protected def newBridge[NODE]():Bridge[NODE]

  private implicit class _Future[T](f:CompletableFuture[T]) {
    def asScala:Future[T] = {
      val promise = Promise[T]()
      f.whenComplete({ (result, ex) => if(ex != null) promise.failure(ex) else promise.success(result) })
      promise.future
    }
  }

  private def randomString(seed:Int, length:Int):String = {
    val random = new scala.util.Random(seed)
    random.nextString(length)
  }

  class QueuedPlug[T] extends Plug[T] {
    private[this] val in = new ArrayBlockingQueue[Message](100)
    private[this] val out = new ArrayBlockingQueue[Message](100)
    private[this] val listeners = mutable.Buffer[Plug.Listener]()

    def send(msg:Message):Unit = {
      logger.debug("send({})", msg)
      out.put(msg)
      if(out.size() == 1) {
        listeners.foreach(_.messageProduceable(this, true))
      }
    }

    def receive():Message = {
      val msg = in.take()
      logger.debug("receive() => {}", msg)
      msg
    }

    override def id():String = BridgeSpec.this.getClass.getName

    override def produce():Message = if(out.isEmpty) {
      listeners.foreach(_.messageProduceable(this, false))
      null
    } else {
      out.take()
    }

    override def consume(msg:Message):Unit = in.put(msg)

    override def addListener(listener:Plug.Listener):Unit = {
      logger.debug("plugin listener bound")
      listeners.append(listener)
    }

    override def removeListener(listener:Plug.Listener):Unit = {
      logger.debug("plugin listener unbound")
      listeners.remove(listeners.indexOf(listener))
      ()
    }

    override def onClose(wire:Wire[T]):Unit = {
      logger.debug("onClose({})", wire)
    }

    override def onException(wire:Wire[T], ex:Throwable):Unit = {
      logger.debug(s"onException($wire, $ex)")
    }
  }

}
