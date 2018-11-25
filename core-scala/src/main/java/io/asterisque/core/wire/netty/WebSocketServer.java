package io.asterisque.core.wire.netty;

import io.asterisque.core.wire.Server;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.SocketAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class WebSocketServer implements Server, WebSocket.Server.Listener {
  private static final Logger logger = LoggerFactory.getLogger(WebSocketServer.class);

  private final AtomicReference<Channel> channel = new AtomicReference<>();

  private final AtomicBoolean closed = new AtomicBoolean(false);

  WebSocketServer() {
  }

  @Override
  public void wsServerReady(@Nonnull Channel channel) {
    logger.trace("WebSocketServer.wsServerReady({})", channel);
    if (!closed.get()) {
      this.channel.set(channel);
    } else {
      logger.debug("websocket server is ready but it has already been closed");
      channel.close();
    }
  }

  @Override
  public void wsServerCaughtException(@Nullable ChannelHandlerContext ctx, @Nonnull Throwable ex) {
    logger.error("WebSocketServer.wsServerCaughtException({}, {})", ctx, ex);
    if (ctx != null) {
      ctx.close();
    }
  }

  @Nullable
  @Override
  public SocketAddress bindAddress() {
    return Optional.ofNullable(channel.get())
        .map(Channel::localAddress)
        .orElse(null);
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      Optional.ofNullable(channel.get()).ifPresent(Channel::close);
    }
  }
}
