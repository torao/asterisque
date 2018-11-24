package io.asterisque.core.wire.netty;

import io.asterisque.core.Debug;
import io.asterisque.core.codec.MessageCodec;
import io.asterisque.core.msg.Message;
import io.asterisque.core.wire.Plug;
import io.asterisque.core.wire.ProtocolException;
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

class WebSocketWire<NODE> implements Wire<NODE> {
  private static final Logger logger = LoggerFactory.getLogger(WebSocketWire.class);

  final CompletableFuture<Wire<NODE>> future = new CompletableFuture<>();

  private final MessageCodec codec;

  private final AtomicReference<Plug<NODE>> plug = new AtomicReference<>();

  private final AtomicReference<ChannelHandlerContext> context = new AtomicReference<>();

  private final AtomicBoolean messageProduceable = new AtomicBoolean();

  final WebSocket.Servant servant = new WebSocket.Servant() {

    @Override
    public void ready(@Nonnull ChannelHandlerContext ctx) {
      context.set(ctx);
      Channel channel = ctx.channel();
      channel.config().setAutoRead(false);

      // 準備が完了したら Future にインスタンスを設定する
      future.complete(WebSocketWire.this);
    }

    @Override
    public void read(@Nonnull ChannelHandlerContext ctx, @Nonnull WebSocketFrame frame) {
      Plug<NODE> plug = WebSocketWire.this.plug.get();
      if (plug == null) {
        throw new IllegalStateException("receiving a websocket frame without plug");
      }
      if (frame instanceof BinaryWebSocketFrame) {
        Optional<Message> msgOpt = frameToMessage((BinaryWebSocketFrame) frame);
        if (msgOpt.isPresent()) {
          Message msg = msgOpt.get();
          logger.trace("read({})", msg);
          plug.consume(msg);
        } else {
          byte[] binary = ByteBufUtil.getBytes(frame.content());
          String msg = String.format(
              "websocket frame doesn't contain enough binaries to restore the message: %s (%d bytes)",
              Debug.toString(binary), binary.length);
          logger.warn(msg);
          plug.onException(WebSocketWire.this, new ProtocolException(msg));
        }
      } else {
        String msg = String.format("unsupported websocket frame: %s", frame);
        logger.warn(msg);
        plug.onException(WebSocketWire.this, new ProtocolException(msg));
      }
    }

    @Override
    public void exception(@Nonnull ChannelHandlerContext ctx, @Nonnull Throwable ex) {
      logger.error("exception callback: {}", ex);
      if (plug.get() != null) {
        plug.get().onException(WebSocketWire.this, ex);
      }
      ctx.channel().close();

      // 失敗を設定
      if (!future.isDone()) {
        future.completeExceptionally(ex);
      }
    }

    @Override
    public void closing(@Nonnull ChannelHandlerContext ctx) {
      if (!future.isDone()) {
        future.completeExceptionally(new IOException("closed"));
      }
    }
  };

  private final Plug.Listener plugListener = new Plug.Listener() {
    @Override
    public void messageProduceable(@Nonnull Plug plug, boolean produceable) {
      messageProduceable.set(produceable);
      if (produceable) {
        pumpUp();
      }
    }

    @Override
    public void messageConsumeable(@Nonnull Plug plug, boolean consumeable) {
      Optional.ofNullable(context.get()).ifPresent(ctx -> ctx.channel().config().setAutoRead(consumeable));
    }
  };

  private final NODE node;
  private final boolean primary;

  WebSocketWire(@Nonnull NODE node, @Nonnull MessageCodec codec, boolean primary) {
    this.node = node;
    this.codec = codec;
    this.primary = primary;
  }

  @Nonnull
  @Override
  public NODE node() {
    return node;
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
    ChannelHandlerContext ctx = context.get();
    if (ctx != null) {
      if (ctx.channel().isOpen()) {
        ctx.channel().close();
      }
      Optional.ofNullable(plug.get()).ifPresent(p -> p.onClose(this));
    }
  }

  @Override
  @Nonnull
  public String toString() {
    // TODO
    return "";
  }

  @Override
  public void bound(@Nonnull Plug<NODE> plug) {
    if (this.plug.compareAndSet(null, plug)) {
      plug.addListener(plugListener);
      prepare();
    } else {
      throw new IllegalStateException("plug has already been set");
    }
  }

  @Override
  public void unbound(@Nonnull Plug<NODE> plug) {
    if (this.plug.compareAndSet(plug, null)) {
      plug.removeListener(plugListener);
    } else {
      throw new IllegalStateException("specified plug is not bound");
    }
  }

  private void prepare() {
    if (plug.get() != null && context.get() != null) {
      context.get().channel().config().setAutoRead(true);
    }
  }

  private void pumpUp() {
    logger.debug("pumpUp()");
    while (true) {
      Plug plug = this.plug.get();
      Channel channel = Optional.ofNullable(this.context.get()).map(ChannelHandlerContext::channel).orElse(null);
      if (plug != null && channel != null && messageProduceable.get() && channel.isWritable()) {
        Message msg = plug.produce();
        if (msg != null) {
          logger.debug("pumpUp(): send {}", msg);
          channel.writeAndFlush(messageToFrame(msg));
        } else {
          logger.debug("pumpUp(): end");
          break;
        }
      } else {
        logger.debug("pumpUp(): end");
        break;
      }
    }
  }

  @Nonnull
  private WebSocketFrame messageToFrame(@Nonnull Message msg) {
    ByteBuffer buffer = codec.encode(msg);
    ByteBuf buf = Unpooled.wrappedBuffer(buffer);
    return new BinaryWebSocketFrame(buf);
  }

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

}
