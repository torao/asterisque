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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class WebSocketServer<NODE> implements Server<NODE> {
  private static final Logger logger = LoggerFactory.getLogger(WebSocketServer.class);

  private final AtomicReference<Channel> channel = new AtomicReference<>();

  final WebSocket.Server.Listener wsListener = new WebSocket.Server.Listener() {
    @Override
    public void ready(@Nonnull Channel channel) {
      logger.trace("Server.Listener.ready({})", channel);
      WebSocketServer.this.channel.set(channel);
    }

    @Override
    public void exception(@Nullable ChannelHandlerContext ctx, @Nonnull Throwable ex) {
      logger.error("Server.Listener.exception({}, {})", ctx, ex);
      if(ctx != null){
        ctx.close();
      }
    }
  };

  private final NODE node;

  WebSocketServer(@Nonnull NODE node) {
    this.node = node;
  }

  @Nonnull
  @Override
  public NODE node() {
    return node;
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
    Optional.ofNullable(channel.get()).ifPresent(Channel::close);
  }
}
