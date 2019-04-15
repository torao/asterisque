package io.asterisque.wire.gateway.netty

import java.io.IOException
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import io.asterisque.utils.Debug
import io.asterisque.wire.ProtocolException
import io.asterisque.wire.gateway.netty.WebSocketWire.logger
import io.asterisque.wire.gateway.{MessageQueue, Wire}
import io.asterisque.wire.message.Message
import io.asterisque.wire.rpc.{CodecException, ObjectMapper}
import io.netty.buffer.{ByteBufUtil, Unpooled}
import io.netty.channel.{Channel, ChannelHandlerContext}
import io.netty.handler.codec.http.websocketx.{BinaryWebSocketFrame, WebSocketFrame}
import io.netty.handler.ssl.SslHandler
import javax.annotation.{Nonnull, Nullable}
import javax.net.ssl.SSLSession
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.concurrent.Promise

private[netty] class WebSocketWire(@Nonnull name:String, primary:Boolean, inboundQueueSize:Int, outboundQueueSize:Int) extends Wire(name, inboundQueueSize, outboundQueueSize) {
  private[this] val promise = Promise[Wire]()
  private[netty] val future = promise.future
  private[this] val context = new AtomicReference[ChannelHandlerContext]()
  private[this] val messagePollable = new AtomicBoolean()
  private[netty] val servant = new WSServant()
  private[this] val closed = new AtomicBoolean()

  inbound.addListener(new InboundListener())
  outbound.addListener(new OutboundListener())

  @Nullable
  override def local:SocketAddress = Option(context.get).map(_.channel.localAddress).orNull

  @Nullable
  override def remote:SocketAddress = Option(context.get).map(_.channel.remoteAddress).orNull

  override def isPrimary:Boolean = primary

  @Nonnull
  override def session:Option[SSLSession] = Option(context.get).flatMap { ctx =>
    val sslHandler = ctx.pipeline.get(classOf[SslHandler])
    if(sslHandler == null) None else Option(sslHandler.engine.getSession)
  }

  override def close():Unit = {
    if(closed.compareAndSet(false, true)) {
      val ctx = context.getAndSet(null)
      if(ctx != null && ctx.channel.isOpen) {
        ctx.channel.close()
      }
      if(!promise.isCompleted) {
        promise.failure(new IOException("the wire closed before connection was established"))
      }
      super.foreach(_.wireClosed(this))
    }
    super.close()
  }

  /**
    * 下層のチャネルが書き込み可能なかぎり、送信キューのメッセージを下層のチャネルに引き渡します。
    */
  private def pumpUp():Unit = {

    @tailrec
    def _pumpUp(channel:Channel):Unit = if(channel.isOpen && channel.isWritable && messagePollable.get()) {
      val msg = outbound.poll()
      if(msg != null) {
        logger.debug("{} >> {}: {}", local, remote, msg)
        channel.writeAndFlush(messageToFrame(msg))
        _pumpUp(channel)
      }
    }

    Option(this.context.get).map(_.channel()).foreach(ch => _pumpUp(ch))
  }

  /**
    * メッセージを WebSocket フレームに変換する。
    *
    * @param msg メッセージ
    * @return WebSocket フレーム
    */
  @Nonnull
  private def messageToFrame(@Nonnull msg:Message):WebSocketFrame = {
    val buffer = ObjectMapper.MESSAGE.encode(msg)
    val buf = Unpooled.wrappedBuffer(buffer)
    new BinaryWebSocketFrame(buf)
  }

  /**
    * WebSocket フレームからメッセージを復元する。
    *
    * @param frame WebSocket フレーム
    * @return メッセージ
    */
  @Nonnull
  private def frameToMessage(@Nonnull frame:BinaryWebSocketFrame):Option[Message] = {
    val buf = frame.content
    val buffer = if(!buf.isDirect) {
      buf.nioBuffer()
    } else {
      val bytes = new Array[Byte](buf.readableBytes)
      buf.readBytes(bytes)
      ByteBuffer.wrap(bytes)
    }
    try {
      Some(ObjectMapper.MESSAGE.decode(buffer.array()))
    } catch {
      case ex:CodecException =>
        logger.warn(s"unsupported websocket frame: ${Debug.toString(buffer.array())}; $ex")
        None
    }
  }

  /**
    * Netty との WebSocket フレーム送受信を行うためのクラス。
    */
  private[netty] class WSServant extends WebSocket.Servant {
    override def wsReady(@Nonnull ctx:ChannelHandlerContext):Unit = {
      logger.trace("wsReady({})", ctx)
      if(closed.get()) {
        ctx.close()
      } else {
        logger.debug(s"connection succeeded: $ctx")
        context.set(ctx)
        val channel = ctx.channel
        channel.config.setAutoRead(true)
        // 準備が完了したら Future にインスタンスを設定する
        promise.success(WebSocketWire.this)
      }
    }

    override def wsFrameReceived(@Nonnull ctx:ChannelHandlerContext, @Nonnull frame:WebSocketFrame):Unit = frame match {
      case frame:BinaryWebSocketFrame =>
        frameToMessage(frame) match {
          case Some(msg) =>
            logger.trace("wsFrameReceived({})", msg)
            inbound.offer(msg)
          case None =>
            val binary = ByteBufUtil.getBytes(frame.content)
            val msg = s"websocket frame doesn't contain enough binaries to restore the message: ${Debug.toString(binary)} (${binary.length} bytes)"
            logger.warn(msg)
            WebSocketWire.super.foreach(_.wireError(WebSocketWire.this, new ProtocolException(msg)))
        }
      case _ =>
        val msg = s"unsupported websocket frame: $frame"
        logger.warn(msg)
        WebSocketWire.super.foreach((listener:Wire.Listener) => listener.wireError(WebSocketWire.this, new ProtocolException(msg)))
    }

    override def wsCaughtException(@Nonnull ctx:ChannelHandlerContext, @Nonnull ex:Throwable):Unit = {
      logger.error(s"wsCaughtException callback from: $ctx", ex)
      // 失敗を設定
      if(!promise.isCompleted) {
        promise.failure(ex)
      }
      WebSocketWire.super.foreach(_.wireError(WebSocketWire.this, ex))
      // チャネルをクローズ
      ctx.channel.close
    }

    override def wsClosed(@Nonnull ctx:ChannelHandlerContext):Unit = if(!promise.isCompleted) {
      promise.failure(new IOException(s"socket closed without any result: $ctx"))
    }
  }

  /**
    * 送信キューからメッセージの取得が可能になったときに下層のチャネルへメッセージの引き渡しを行うリスナ。
    */
  private class OutboundListener extends MessageQueue.Listener {
    override def messagePollable(@Nonnull messageQueue:MessageQueue, pollable:Boolean):Unit = {
      WebSocketWire.this.messagePollable.set(pollable)
      if(pollable) {
        pumpUp()
      }
    }
  }

  /**
    * 受信キューが受付可能になったかどうかで下層のチャネルからの自動読み出しを設定するリスナ。
    */
  private class InboundListener extends MessageQueue.Listener {
    override def messageOfferable(@Nonnull messageQueue:MessageQueue, offerable:Boolean):Unit = {
      Option(context.get).foreach(_.channel.config.setAutoRead(offerable))
    }
  }

}

object WebSocketWire {
  private val logger = LoggerFactory.getLogger(classOf[WebSocketWire])
}

