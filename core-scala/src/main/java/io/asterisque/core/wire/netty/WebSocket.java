package io.asterisque.core.wire.netty;

import io.asterisque.utils.Debug;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLEngine;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class WebSocket {

  private static final String SUBPROTOCOL = "v10.asterisque";

  private WebSocket() {
  }

  /**
   * WebSocket プロトコルを使用するサーバクラス。
   */
  public static class Server {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);

    public static final Listener EmptyListener = new Listener() {
      @Override
      public void wsServerReady(@Nonnull Channel ch) {
        logger.trace("wsServerReady({})", ch);
      }

      @Override
      public void wsServerCaughtException(@Nullable ChannelHandlerContext ctx, @Nonnull Throwable ex) {
        logger.trace("wsServerCaughtException({},{})", ctx, ex);
      }
    };

    private final EventLoopGroup group;
    private Channel channel;

    private final String subprotocol;
    private final String path;
    private final Server.Listener listener;
    private final SslContext sslContext;

    /**
     * WebSocket プロトコルを使用するサーバクラス。
     *
     * @param group       サーバで使用するイベントループ
     * @param subprotocol asterisque 上で使用するサブプロトコル
     * @param path        WebSocket が使用する HTTP URI パス
     * @param listener    サーバイベントリスナ
     * @param sslContext  SSL/TLS を使用する場合は有効な {@link SslContext}、使用しない場合は null
     */
    public Server(@Nonnull EventLoopGroup group,
                  @Nonnull String subprotocol, @Nonnull String path, @Nonnull Server.Listener listener,
                  @Nullable SslContext sslContext) {
      if (!path.startsWith("/")) {
        throw new IllegalArgumentException("the server path must begin with '/': '" + path + "'");
      }
      this.group = group;
      this.subprotocol = subprotocol;
      this.path = path;
      this.listener = listener;
      this.sslContext = sslContext;
    }

    /**
     * SSL/TLS を使用せず WebSocket プロトコルを使用するサーバクラス。
     *
     * @param group       サーバで使用するイベントループ
     * @param subprotocol asterisque 上で使用するサブプロトコル
     * @param path        WebSocket が使用する HTTP URI パス
     */
    public Server(@Nonnull EventLoopGroup group, @Nonnull String subprotocol, @Nonnull String path) {
      this(group, subprotocol, path, EmptyListener, null);
    }

    /**
     * 指定されたソケットアドレスにサーバソケットを bind します。bind に成功した場合 {@link Listener#wsServerReady(Channel)}
     * コールバックが発生します。
     * bind に失敗した場合は例外の Future を返します ({@link Listener} へのコールバックではありません)。
     *
     * @param address  バインドアドレス
     * @param onAccept accept ごとに呼び出されチャネルから Servant を生成する
     * @return サーバソケットのチャネル
     */
    public CompletableFuture<Channel> bind(@Nonnull SocketAddress address,
                                           @Nonnull Function<Channel, Servant> onAccept) {
      logger.trace("bind({})", address);

      ServerBootstrap bootstrap = new ServerBootstrap();
      bootstrap.group(group)
          .channel(NioServerSocketChannel.class)
          .handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
              listener.wsServerReady(ch);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
              listener.wsServerCaughtException(ctx, cause);
              super.exceptionCaught(ctx, cause);
            }
          })
          .childHandler(new Initializer(subprotocol, path, sslContext, onAccept));

      return channelFutureToFuture(bootstrap.bind(address)).whenComplete((ch, ex) -> {
        if (ex == null) {
          channel = ch;
          logger.debug("the server has completed binding: {}", channel);
        } else {
          listener.wsServerCaughtException(null, ex);
        }
      });
    }

    public void destroy() {
      logger.trace("destroy()");
      if (channel != null) {
        channel.close();
      }
    }

    public interface Listener {
      void wsServerReady(@Nonnull Channel ch);

      void wsServerCaughtException(@Nullable ChannelHandlerContext ctx, @Nonnull Throwable ex);
    }

    /**
     * WebSocket サーバが接続を受け付けたときにチャネルの初期化を行います。
     */
    private static class Initializer extends ChannelInitializer<Channel> {
      private static final Logger logger = LoggerFactory.getLogger(Initializer.class);

      private final String subprotocol;
      private final String path;
      private final SslContext sslContext;
      private final Function<Channel, Servant> onAccept;

      private Initializer(@Nonnull String subprotocol, @Nonnull String path, @Nullable SslContext sslContext,
                          @Nonnull Function<Channel, Servant> onAccept) {
        this.subprotocol = subprotocol;
        this.path = path;
        this.sslContext = sslContext;
        this.onAccept = onAccept;
      }

      @Override
      protected void initChannel(@Nonnull Channel ch) {
        logger.trace("initChannel({})", ch);
        Servant servant = onAccept.apply(ch);
        String subprotocol = SUBPROTOCOL + "," + this.subprotocol;
        if (sslContext != null) {
          SSLEngine engine = sslContext.newEngine(ch.alloc());
          ch.pipeline().addFirst("tls", new SslHandler(engine));
        }
        ch.pipeline()
            .addLast("http", new HttpServerCodec())
            .addLast("http-chunked", new ChunkedWriteHandler())
            .addLast("aggregator", new HttpObjectAggregator(64 * 1024))
            .addLast("request", new HttpRequestHandler(path))
            .addLast("websocket", new WebSocketServerProtocolHandler(path, subprotocol))
            .addLast("io.asterisque.server", new WebSocketFrameHandler(servant));
      }
    }
  }

  /**
   * WebSocket プロトコルを使用するクライアントクラス。
   */
  public static class Client {
    private static final Logger logger = LoggerFactory.getLogger(Client.class);

    private final EventLoopGroup group;
    private Channel channel;

    private final String subprotocol;
    private final Servant servant;
    private final SslContext sslContext;

    /**
     * @param group       クライアントで使用するイベントループ
     * @param subprotocol asterisque 上で使用するサブプロトコル
     * @param servant     WebSocket イベントごとのコールバック
     * @param sslContext  SSL/TLS を使用する場合は有効な {@link SslContext}、使用しない場合は null
     */
    public Client(@Nonnull EventLoopGroup group, @Nonnull String subprotocol, @Nonnull Servant servant,
                  @Nullable SslContext sslContext) {
      this.group = group;
      this.subprotocol = subprotocol;
      this.servant = servant;
      this.sslContext = sslContext;
    }

    public CompletableFuture<Channel> connect(@Nonnull URI uri) {
      logger.trace("connect({})", uri);

      Bootstrap bootstrap = new Bootstrap().group(group).channel(NioSocketChannel.class)
          .handler(new Initializer(uri, subprotocol, servant, sslContext));

      return channelFutureToFuture(bootstrap.connect(new InetSocketAddress(uri.getHost(), uri.getPort())))
          .whenComplete((ch, ex) -> {
            if (ex != null) {
              channel = ch;
            }
          });
    }

    public void destroy() {
      logger.trace("destroy()");
      if (channel != null) {
        channel.close();
      }
    }

    /**
     * WebSocket サーバと接続したときにチャネルの初期化を行います。
     */
    private static class Initializer extends ChannelInitializer<Channel> {
      private static final Logger logger = LoggerFactory.getLogger(Initializer.class);

      private final URI uri;
      private final String subprotocol;
      private final Servant servant;
      private final SslContext sslContext;

      private Initializer(@Nonnull URI uri, @Nonnull String subprotocol, @Nonnull Servant servant,
                          @Nullable SslContext sslContext) {
        this.uri = uri;
        this.subprotocol = subprotocol;
        this.servant = servant;
        this.sslContext = sslContext;
      }

      @Override
      protected void initChannel(@Nonnull Channel ch) {
        logger.trace("initChannel({})", ch);
        String subprotocol = SUBPROTOCOL + ", " + this.subprotocol;
        if (sslContext != null) {
          SSLEngine engine = sslContext.newEngine(ch.alloc());
          ch.pipeline().addFirst("tls", new SslHandler(engine));
        }
        ch.pipeline()
            .addLast("http", new HttpClientCodec())
            .addLast("http-chunked", new ChunkedWriteHandler())
            .addLast("aggregator", new HttpObjectAggregator(64 * 1024))
            .addLast("websocket", new WebSocketClientProtocolHandler(uri, WebSocketVersion.V13, subprotocol,
                false, EmptyHttpHeaders.INSTANCE, 64 * 1024))
            .addLast("io.asterisque.client", new WebSocketFrameHandler(servant));
      }
    }
  }

  /**
   * WebSocket の双方のエンドポイントでフレームの送受信を行うためのインターフェース。
   */
  public interface Servant {
    /**
     * WebSocket ハンドシェイクが完了し WebSocket のフレームが送受信可能になったときに呼び出されます。
     *
     * @param ctx WebSocket プロトコルの準備が完了したコンテキスト
     */
    void wsReady(@Nonnull ChannelHandlerContext ctx);

    /**
     * 相手側から WebSocket のフレームを受信したときに呼び出されます。
     *
     * @param ctx フレームを受信したコンテキスト
     * @param msg 受信したフレーム
     */
    void wsFrameReceived(@Nonnull ChannelHandlerContext ctx, @Nonnull WebSocketFrame msg);

    /**
     * 指定されたチャネルがクローズされているときに呼び出されます。
     *
     * @param ctx クローズされているチャネル
     */
    void wsClosed(@Nonnull ChannelHandlerContext ctx);

    /**
     * 指定されたコンテキスト上で例外が発生したときに呼び出されます。
     *
     * @param ctx 例外の発生したコンテキスト
     * @param ex  発生した例外
     */
    void wsCaughtException(@Nonnull ChannelHandlerContext ctx, @Nonnull Throwable ex);
  }

  /**
   * WebSocket のフレームを受信します。
   * WS ハンドシェイクが完了したときに HttpRequestHandler をパイプラインから取り除く。
   */
  private static class WebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketFrameHandler.class);

    private final Servant servant;

    private WebSocketClientProtocolHandler.ClientHandshakeStateEvent handshakeState;

    private WebSocketFrameHandler(@Nonnull Servant servant) {
      this.servant = servant;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      logger.trace("channelActive({})", ctx);
      super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      logger.trace("channelInactive({})", ctx);
      if (handshakeState == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_ISSUED) {
        servant.wsCaughtException(ctx, new HandshakeException("WebSocket handshake failure"));
      }
      servant.wsClosed(ctx);
      super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      logger.trace("exceptionCaught({},{})", ctx, cause);
      servant.wsCaughtException(ctx, cause);
      super.exceptionCaught(ctx, cause);
    }

    @Override
    public void userEventTriggered(@Nonnull ChannelHandlerContext ctx, @Nonnull Object evt) throws Exception {
      logger.trace("userEventTriggered({},{})", ctx, evt);
      if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
        logger.trace("server handshake complete");
        handshakeState = WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE;
        ctx.pipeline().remove(HttpRequestHandler.class);
        servant.wsReady(ctx);
      } else if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
        logger.trace("client handshake complete");
        handshakeState = WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE;
        servant.wsReady(ctx);
      } else if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_ISSUED) {
        logger.trace("client handshake uncompleted");
        handshakeState = WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_ISSUED;
        // HANDSHAKE_ISSUED の後に HANDSHAKE_COMPLETE が来るためこの時点ではエラーではない
      } else {
        super.userEventTriggered(ctx, evt);
      }
    }

    @Override
    protected void channelRead0(@Nonnull ChannelHandlerContext ctx, @Nonnull WebSocketFrame msg) {
      logger.trace("channelRead0({},{})", ctx, msg);
      if (logger.isTraceEnabled()) {
        if (msg instanceof TextWebSocketFrame) {
          logger.trace("RECEIVE: {}", Debug.toString(((TextWebSocketFrame) msg).text()));
        } else {
          ByteBuf buf = msg.content();
          logger.trace("RECEIVE: {}", Debug.toString(ByteBufUtil.getBytes(buf)));
        }
      }

      servant.wsFrameReceived(ctx, msg);
    }
  }

  /**
   * WebSocket ハンドシェイクの前段階で WebSocket 用の HTTP リクエストパスのみを許可するハンドラ。ハンドシェイク後は
   * パイプラインから取り除かれる。
   */
  private static class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final String path;

    private HttpRequestHandler(@Nonnull String path) {
      this.path = path;
    }

    @Override
    public void channelRead0(@Nonnull ChannelHandlerContext ctx, @Nonnull FullHttpRequest request) {
      if (path.equalsIgnoreCase(request.uri())) {
        ctx.fireChannelRead(request.retain());
      } else {
        String msg = "asterisque WebSocket bridge doesn't work for the specified URI: " + request.uri();
        ctx.writeAndFlush(HTTP.newErrorResponse(HttpResponseStatus.FORBIDDEN, msg))
            .addListener(future -> ctx.disconnect());
      }
    }
  }

  /**
   * Netty の ChannelFuture を CompletableFuture に変換する。
   *
   * @param cf ChannelFuture
   * @return CompletableFuture
   */
  @Nonnull
  private static CompletableFuture<Channel> channelFutureToFuture(@Nonnull ChannelFuture cf) {
    CompletableFuture<Channel> future = new CompletableFuture<>();
    cf.addListener(ignore -> {
      if (cf.isSuccess()) {
        future.complete(cf.channel());
      } else {
        future.completeExceptionally(cf.cause());
      }
    });
    return future;
  }

  /**
   * WebSocket のハンドシェイクに失敗したときに発生する例外。
   */
  public static class HandshakeException extends RuntimeException {
    public HandshakeException(@Nonnull String msg) {
      super(msg);
    }
  }
}
