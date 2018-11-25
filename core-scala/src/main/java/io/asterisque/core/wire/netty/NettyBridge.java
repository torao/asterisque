package io.asterisque.core.wire.netty;

import io.asterisque.Asterisque;
import io.asterisque.core.codec.MessageCodec;
import io.asterisque.core.wire.Bridge;
import io.asterisque.core.wire.Server;
import io.asterisque.core.wire.UnsupportedProtocolException;
import io.asterisque.core.wire.Wire;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class NettyBridge implements Bridge {
  private static final Logger logger = LoggerFactory.getLogger(NettyBridge.class);

  /**
   * この Bridge から生成される Wire で共通して使用されるイベントループです。
   */
  @Nonnull
  private final NioEventLoopGroup worker;

  /**
   * この Bridge に終了命令が出されていることを示すブール値です。
   */
  private final AtomicBoolean closing = new AtomicBoolean(false);

  public NettyBridge() {
    this.worker = new NioEventLoopGroup();
  }

  /**
   * @param uri         接続先の URI
   * @param subprotocol asterisque 上で実装しているサブプロトコル
   * @param sslContext  Secure ソケットを示す URI スキーマが指定された場合 (例えば {@code wss://}) に使用する SSL コンテキスト
   * @return Wire の Future
   */
  @Override
  @Nonnull
  public CompletableFuture<Wire> newWire(@Nonnull URI uri, @Nonnull String subprotocol,
                                         int inboundQueueSize, int outboundQueueSize,
                                         @Nullable SSLContext sslContext) {
    return getScheme(uri).thenCompose(scheme -> {
      if (scheme.equals("ws") || scheme.equals("wss")) {
        WebSocketWire wire = new WebSocketWire(
            MessageCodec.MessagePackCodec, false, inboundQueueSize, outboundQueueSize);
        SslContext ssl = null;
        if (scheme.equals("wss")) {
          ssl = Optional.ofNullable(sslContext)
              .map(ctx -> (SslContext) new JdkSslContext(ctx, true, ClientAuth.NONE))
              .orElse(getDefaultClientSSL());
        }
        WebSocket.Client driver = new WebSocket.Client(worker(), subprotocol, wire.servant, ssl);
        driver.connect(uri);
        return wire.future;
      }

      return Asterisque.Future.fail(new UnsupportedProtocolException("unsupported uri scheme: " + uri));
    });
  }

  /**
   * @param uri         接続先の URI
   * @param subprotocol asterisque 上で実装しているサブプロトコル
   * @param sslContext  Secure ソケットを示す URI スキーマが指定された場合 (例えば {@code wss://}) に使用する SSL コンテキスト
   * @param onAccept    サーバが接続を受け付けたときのコールバック
   * @return Server の Future
   */
  @Override
  @Nonnull
  public CompletableFuture<Server> newServer(@Nonnull URI uri, @Nonnull String subprotocol,
                                             int inboundQueueSize, int outboundQueueSize,
                                             @Nullable SSLContext sslContext,
                                             @Nonnull Consumer<CompletableFuture<Wire>> onAccept) {
    return getScheme(uri).thenCompose(scheme -> {
      if (scheme.equals("ws") || scheme.equals("wss")) {
        WebSocketServer server = new WebSocketServer();
        SslContext ssl = null;
        if (scheme.equals("wss")) {
          ssl = Optional.ofNullable(sslContext)
              .map(ctx -> (SslContext) new JdkSslContext(ctx, false, ClientAuth.NONE))
              .orElseThrow(() -> new NullPointerException("ssl context isn't specified"));
        }
        String path = Optional.ofNullable(uri.getPath())
            .filter(p -> p.length() != 0)
            .orElse("/");
        WebSocket.Server driver = new WebSocket.Server(worker(), subprotocol, path, server, ssl);

        return driver.bind(uriToSocketAddress(uri), channel -> {
          WebSocketWire wire = new WebSocketWire(
              MessageCodec.MessagePackCodec, true, inboundQueueSize, outboundQueueSize);
          onAccept.accept(wire.future);
          return wire.servant;
        }).thenApply(ch -> server);
      }

      return Asterisque.Future.fail(new UnsupportedProtocolException("unsupported uri scheme: " + uri));
    });
  }

  @Override
  public void close() {
    logger.trace("close()");
    if (closing.compareAndSet(false, true)) {
      worker.shutdownGracefully();
    }
  }

  @Nonnull
  private CompletableFuture<String> getScheme(@Nonnull URI uri) {
    if (closing.get()) {
      return Asterisque.Future.fail(new IOException("bridge has been wsClosed"));
    }
    String scheme = uri.getScheme();
    if (scheme == null) {
      return Asterisque.Future.fail(new IllegalArgumentException("uri scheme was not specified: " + uri));
    }
    return CompletableFuture.completedFuture(scheme.toLowerCase());
  }

  @Nonnull
  private SslContext getDefaultClientSSL() {
    try {
      return SslContextBuilder.forClient().build();
    } catch (SSLException ex) {
      throw new IllegalStateException("default client ssl/tls is not available", ex);
    }
  }

  @Nonnull
  private NioEventLoopGroup worker() {
    if (closing.get()) {
      throw new IllegalStateException("netty bridge has been wsClosed");
    }
    return worker;
  }

  @Nonnull
  private InetSocketAddress uriToSocketAddress(@Nonnull URI uri) {
    if (uri.getHost() == null || uri.getHost().length() == 0) {
      return new InetSocketAddress(uri.getPort());
    }
    return new InetSocketAddress(uri.getHost(), uri.getPort());
  }

}