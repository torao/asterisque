package io.asterisque.core.wire.netty;

import io.asterisque.core.Debug;
import io.asterisque.core.ProtocolException;
import io.asterisque.core.codec.MessageCodec;
import io.asterisque.core.msg.Message;
import io.asterisque.core.wire.MessageQueue;
import io.asterisque.core.wire.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class WebSocketWire extends Wire {
  private static final Logger logger = LoggerFactory.getLogger(WebSocketWire.class);

  final CompletableFuture<Wire> future = new CompletableFuture<>();

  private final MessageCodec codec;

  private final AtomicReference<ChannelHandlerContext> context = new AtomicReference<>();

  private final AtomicBoolean messagePollable = new AtomicBoolean();

  final WebSocket.Servant servant = new WSServant();

  private final boolean primary;

  private final AtomicBoolean closed = new AtomicBoolean();

  WebSocketWire(@Nonnull MessageCodec codec, boolean primary, int inboundQueueSize, int outboundQueueSize) {
    super(inboundQueueSize, outboundQueueSize);
    this.codec = codec;
    this.primary = primary;

    super.inbound.addListener(new InboundListener());
    super.outbound.addListener(new OutboundListener());
  }

  @Nullable
  @Override
  public SocketAddress local() {
    return Optional.ofNullable(context.get()).map(ctx -> ctx.channel().localAddress()).orElse(null);
  }

  @Nullable
  @Override
  public SocketAddress remote() {
    return Optional.ofNullable(context.get()).map(ctx -> ctx.channel().remoteAddress()).orElse(null);
  }

  @Override
  public boolean isPrimary() {
    return primary;
  }

  @Nonnull
  @Override
  public Optional<SSLSession> session() {
    return Optional.ofNullable(context.get()).flatMap(ctx -> {
      SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
      if (sslHandler != null) {
        return Optional.ofNullable(sslHandler.engine().getSession());
      }
      return Optional.empty();
    });
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      ChannelHandlerContext ctx = context.getAndSet(null);
      if (ctx != null) {
        if (ctx.channel().isOpen()) {
          ctx.channel().close();
        }
      }
      if (!future.isDone()) {
        future.completeExceptionally(new IOException("the wire closed before connection was established"));
      }
      fireWireEvent(listener -> listener.wireClosed(WebSocketWire.this));
    }
    super.close();
  }

  /**
   * 下層のチャネルが書き込み可能なかぎり、送信キューのメッセージを下層のチャネルに引き渡します。
   */
  private void pumpUp() {
    Channel channel = Optional.ofNullable(this.context.get()).map(ChannelHandlerContext::channel).orElse(null);
    while (channel != null && channel.isOpen() && channel.isWritable() && messagePollable.get()) {
      Message msg = super.outbound.poll();
      if (msg != null) {
        logger.trace("pumpUp(): send {}", msg);
        channel.writeAndFlush(messageToFrame(msg));
      } else {
        break;
      }
    }
  }

  /**
   * メッセージを WebSocket フレームに変換する。
   *
   * @param msg メッセージ
   * @return WebSocket フレーム
   */
  @Nonnull
  private WebSocketFrame messageToFrame(@Nonnull Message msg) {
    ByteBuffer buffer = codec.encode(msg);
    ByteBuf buf = Unpooled.wrappedBuffer(buffer);
    return new BinaryWebSocketFrame(buf);
  }

  /**
   * WebSocket フレームからメッセージを復元する。
   *
   * @param frame WebSocket フレーム
   * @return メッセージ
   */
  @Nonnull
  private Optional<Message> frameToMessage(@Nonnull BinaryWebSocketFrame frame) {
    ByteBuf buf = frame.content();
    ByteBuffer buffer;
    if (buf.isDirect()) {
      buffer = buf.nioBuffer();
    } else {
      byte[] bytes = new byte[buf.readableBytes()];
      buf.readBytes(bytes);
      buffer = ByteBuffer.wrap(bytes);
    }
    return codec.decode(buffer);
  }

  /**
   * Netty との WebSocket フレーム送受信を行うためのクラス。
   */
  private class WSServant implements WebSocket.Servant {

    @Override
    public void wsReady(@Nonnull ChannelHandlerContext ctx) {
      if (closed.get()) {
        ctx.close();
      } else {
        context.set(ctx);
        Channel channel = ctx.channel();
        channel.config().setAutoRead(true);

        // 準備が完了したら Future にインスタンスを設定する
        future.complete(WebSocketWire.this);
      }
    }

    @Override
    public void wsFrameReceived(@Nonnull ChannelHandlerContext ctx, @Nonnull WebSocketFrame frame) {
      if (frame instanceof BinaryWebSocketFrame) {
        Optional<Message> msgOpt = frameToMessage((BinaryWebSocketFrame) frame);
        if (msgOpt.isPresent()) {
          Message msg = msgOpt.get();
          logger.trace("wsFrameReceived({})", msg);
          inbound.offer(msg);
        } else {
          byte[] binary = ByteBufUtil.getBytes(frame.content());
          String msg = String.format(
              "websocket frame doesn't contain enough binaries to restore the message: %s (%d bytes)",
              Debug.toString(binary), binary.length);
          logger.warn(msg);
          fireWireEvent(listener -> listener.wireError(WebSocketWire.this, new ProtocolException(msg)));
        }
      } else {
        String msg = String.format("unsupported websocket frame: %s", frame);
        logger.warn(msg);
        fireWireEvent(listener -> listener.wireError(WebSocketWire.this, new ProtocolException(msg)));
      }
    }

    @Override
    public void wsCaughtException(@Nonnull ChannelHandlerContext ctx, @Nonnull Throwable ex) {
      logger.error("wsCaughtException callback: {}", ex);
      fireWireEvent(listener -> listener.wireError(WebSocketWire.this, ex));
      ctx.channel().close();

      // 失敗を設定
      if (!future.isDone()) {
        future.completeExceptionally(ex);
      }
    }

    @Override
    public void wsClosed(@Nonnull ChannelHandlerContext ctx) {
      if (!future.isDone()) {
        future.completeExceptionally(new IOException("wsClosed"));
      }
    }
  }

  /**
   * 送信キューからメッセージの取得が可能になったときに下層のチャネルへメッセージの引き渡しを行うリスナ。
   */
  private class OutboundListener implements MessageQueue.Listener {
    @Override
    public void messagePollable(@Nonnull MessageQueue messageQueue, boolean pollable) {
      messagePollable.set(pollable);
      if (pollable) {
        pumpUp();
      }
    }
  }

  /**
   * 受信キューが受付可能になったかどうかで下層のチャネルからの自動読み出しを設定するリスナ。
   */
  private class InboundListener implements MessageQueue.Listener {
    @Override
    public void messageOfferable(@Nonnull MessageQueue messageQueue, boolean offerable) {
      Optional.ofNullable(context.get()).ifPresent(ctx -> ctx.channel().config().setAutoRead(offerable));
    }
  }

}
