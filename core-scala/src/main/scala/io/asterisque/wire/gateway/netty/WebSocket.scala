package io.asterisque.wire.gateway.netty

import java.net.{InetSocketAddress, SocketAddress, URI}
import java.util.function.Function

import io.asterisque.core.wire.netty.HTTP
import io.asterisque.utils.Debug
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.buffer.ByteBufUtil
import io.netty.channel._
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler.ClientHandshakeStateEvent
import io.netty.handler.codec.http.websocketx._
import io.netty.handler.ssl.{SslContext, SslHandler}
import io.netty.handler.stream.ChunkedWriteHandler
import javax.annotation.{Nonnull, Nullable}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

object WebSocket {
  private[this] val SUBPROTOCOL = "v10.asterisque"

  /**
    * WebSocket プロトコルを使用するサーバクラス。
    */
  object Server {
    private[Server] val logger = LoggerFactory.getLogger(classOf[WebSocket.Server])

    val EmptyListener:Server.Listener = new Server.Listener() {
      override def wsServerReady(@Nonnull ch:Channel):Unit = {
        logger.trace("wsServerReady({})", ch)
      }

      override def wsServerCaughtException(@Nullable ctx:ChannelHandlerContext, @Nonnull ex:Throwable):Unit = {
        logger.trace("wsServerCaughtException({},{})", ctx, ex)
      }
    }

    trait Listener {
      def wsServerReady(@Nonnull ch:Channel):Unit

      def wsServerCaughtException(@Nullable ctx:ChannelHandlerContext, @Nonnull ex:Throwable):Unit
    }

    /**
      * WebSocket サーバが接続を受け付けたときにチャネルの初期化を行います。
      */
    private[Server] class Initializer private(val subprotocol:String, val path:String, val sslContext:SslContext, val onAccept:Function[Channel, WebSocket.Servant]) extends ChannelInitializer[Channel] {
      override protected def initChannel(@Nonnull ch:Channel):Unit = {
        logger.trace("initChannel({})", ch)
        val servant = onAccept.apply(ch)
        val subprotocol = SUBPROTOCOL + "," + this.subprotocol
        if(sslContext != null) {
          val engine = sslContext.newEngine(ch.alloc)
          ch.pipeline.addFirst("tls", new SslHandler(engine))
        }
        ch.pipeline
          .addLast("http", new HttpServerCodec)
          .addLast("http-chunked", new ChunkedWriteHandler)
          .addLast("aggregator", new HttpObjectAggregator(64 * 1024))
          .addLast("request", new WebSocket.HttpRequestHandler(path))
          .addLast("websocket", new WebSocketServerProtocolHandler(path, subprotocol))
          .addLast("io.asterisque.server", new WebSocket.WebSocketFrameHandler(servant))
      }
    }

  }

  /**
    * WebSocket プロトコルを使用するサーバクラス。
    *
    * @param group       サーバで使用するイベントループ
    * @param subprotocol asterisque 上で使用するサブプロトコル
    * @param path        WebSocket が使用する HTTP URI パス
    * @param listener    サーバイベントリスナ
    * @param sslContext  SSL/TLS を使用する場合は有効な { @link SslContext}、使用しない場合は null
    */
  class Server(val group:EventLoopGroup, val subprotocol:String, val path:String, val listener:Server.Listener, val sslContext:SslContext) {
    if(!path.startsWith("/")) {
      throw new IllegalArgumentException(s"the server path must begin with '/': '$path'")
    }

    private var channel:Channel = _

    /**
      * SSL/TLS を使用せず WebSocket プロトコルを使用するサーバクラス。
      *
      * @param group       サーバで使用するイベントループ
      * @param subprotocol asterisque 上で使用するサブプロトコル
      * @param path        WebSocket が使用する HTTP URI パス
      */
    def this(@Nonnull group:EventLoopGroup, @Nonnull subprotocol:String, @Nonnull path:String) {
      this(group, subprotocol, path, Server.EmptyListener, null)
    }

    /**
      * 指定されたソケットアドレスにサーバソケットを bind します。bind に成功した場合 [[Server.Listener.wsServerReady()]]
      * コールバックが発生します。
      * bind に失敗した場合は例外の Future を返します ([[Server.Listener]] へのコールバックではありません)。
      *
      * @param address  バインドアドレス
      * @param onAccept accept ごとに呼び出されチャネルから Servant を生成する
      * @return サーバソケットのチャネル
      */
    def bind(@Nonnull address:SocketAddress, @Nonnull onAccept:Channel => WebSocket.Servant):Future[Channel] = {
      Server.logger.trace("bind({})", address)

      val bootstrap = new ServerBootstrap()
        .group(group)
        .channel(classOf[NioServerSocketChannel])
        .handler(new ChannelInitializer[Channel]() {
          override protected def initChannel(ch:Channel):Unit = {
            listener.wsServerReady(ch)
            super.initChannel(ch)
          }

          override def exceptionCaught(ctx:ChannelHandlerContext, cause:Throwable):Unit = {
            listener.wsServerCaughtException(ctx, cause)
            super.exceptionCaught(ctx, cause)
          }
        })
        .childHandler(new Server.Initializer(subprotocol, path, sslContext, onAccept))

      channelFutureToFuture(bootstrap.bind(address)).andThen {
        case Success(ch) =>
          channel = ch
          Server.logger.debug("the server has completed binding: {}", channel)
        case Failure(ex) =>
          listener.wsServerCaughtException(null, ex)
      }
    }

    def destroy():Unit = {
      Server.logger.trace("destroy()")
      if(channel != null) {
        channel.close()
      }
    }
  }

  /**
    * WebSocket プロトコルを使用するクライアントクラス。
    */
  object Client {
    private val logger = LoggerFactory.getLogger(classOf[WebSocket.Client])

    /**
      * WebSocket サーバと接続したときにチャネルの初期化を行います。
      */
    private[Client] class Initializer private(val uri:URI, val subprotocol:String, val servant:WebSocket.Servant, val sslContext:SslContext) extends ChannelInitializer[Channel] {
      override protected def initChannel(@Nonnull ch:Channel):Unit = {
        logger.trace("initChannel({})", ch)
        val subprotocol = s"$SUBPROTOCOL, ${this.subprotocol}"
        if(sslContext != null) {
          val engine = sslContext.newEngine(ch.alloc)
          ch.pipeline.addFirst("tls", new SslHandler(engine))
        }
        ch.pipeline
          .addLast("http", new HttpClientCodec)
          .addLast("http-chunked", new ChunkedWriteHandler)
          .addLast("aggregator", new HttpObjectAggregator(64 * 1024))
          .addLast("websocket", new WebSocketClientProtocolHandler(uri, WebSocketVersion.V13, subprotocol, false, EmptyHttpHeaders.INSTANCE, 64 * 1024))
          .addLast("io.asterisque.client", new WebSocket.WebSocketFrameHandler(servant))
      }
    }

  }

  /**
    * @param group       クライアントで使用するイベントループ
    * @param subprotocol asterisque 上で使用するサブプロトコル
    * @param servant     WebSocket イベントごとのコールバック
    * @param sslContext  SSL/TLS を使用する場合は有効な { @link SslContext}、使用しない場合は null
    */
  class Client(val group:EventLoopGroup, val subprotocol:String, val servant:WebSocket.Servant, val sslContext:SslContext) {
    private var channel:Channel = _

    def connect(@Nonnull uri:URI):Future[Channel] = {
      Client.logger.trace("connect({})", uri)
      val bootstrap = new Bootstrap()
        .group(group)
        .channel(classOf[NioSocketChannel])
        .handler(new Client.Initializer(uri, subprotocol, servant, sslContext))
      channelFutureToFuture(bootstrap.connect(new InetSocketAddress(uri.getHost, uri.getPort))).andThen {
        case Success(ch) =>
          channel = ch
          Client.logger.debug("the client has completed binding: {}", channel)
        case Failure(ex) => ()
      }
    }

    def destroy():Unit = {
      Client.logger.trace("destroy()")
      if(channel != null) {
        channel.close()
      }
    }
  }

  /**
    * WebSocket の双方のエンドポイントでフレームの送受信を行うためのインターフェース。
    */
  trait Servant {

    /**
      * WebSocket ハンドシェイクが完了し WebSocket のフレームが送受信可能になったときに呼び出されます。
      *
      * @param ctx WebSocket プロトコルの準備が完了したコンテキスト
      */
    def wsReady(@Nonnull ctx:ChannelHandlerContext):Unit

    /**
      * 相手側から WebSocket のフレームを受信したときに呼び出されます。
      *
      * @param ctx フレームを受信したコンテキスト
      * @param msg 受信したフレーム
      */
    def wsFrameReceived(@Nonnull ctx:ChannelHandlerContext, @Nonnull msg:WebSocketFrame):Unit

    /**
      * 指定されたチャネルがクローズされているときに呼び出されます。
      *
      * @param ctx クローズされているチャネル
      */
    def wsClosed(@Nonnull ctx:ChannelHandlerContext):Unit

    /**
      * 指定されたコンテキスト上で例外が発生したときに呼び出されます。
      *
      * @param ctx 例外の発生したコンテキスト
      * @param ex  発生した例外
      */
    def wsCaughtException(@Nonnull ctx:ChannelHandlerContext, @Nonnull ex:Throwable):Unit
  }

  /**
    * WebSocket のフレームを受信します。
    * WS ハンドシェイクが完了したときに HttpRequestHandler をパイプラインから取り除く。
    */
  private object WebSocketFrameHandler {
    private val logger = LoggerFactory.getLogger(classOf[WebSocket.WebSocketFrameHandler])
  }

  private class WebSocketFrameHandler private(val servant:WebSocket.Servant) extends SimpleChannelInboundHandler[WebSocketFrame] {
    private var handshakeState:ClientHandshakeStateEvent = _

    @throws[Exception]
    override def channelActive(ctx:ChannelHandlerContext):Unit = {
      WebSocketFrameHandler.logger.trace("channelActive({})", ctx)
      super.channelActive(ctx)
    }

    @throws[Exception]
    override def channelInactive(ctx:ChannelHandlerContext):Unit = {
      WebSocketFrameHandler.logger.trace("channelInactive({})", ctx)
      if(handshakeState == ClientHandshakeStateEvent.HANDSHAKE_ISSUED) {
        servant.wsCaughtException(ctx, new WebSocket.HandshakeException("WebSocket handshake failure"))
      }
      servant.wsClosed(ctx)
      super.channelInactive(ctx)
    }

    @throws[Exception]
    override def exceptionCaught(ctx:ChannelHandlerContext, cause:Throwable):Unit = {
      WebSocketFrameHandler.logger.trace("exceptionCaught({},{})", ctx, cause)
      servant.wsCaughtException(ctx, cause)
      super.exceptionCaught(ctx, cause)
    }

    @throws[Exception]
    override def userEventTriggered(@Nonnull ctx:ChannelHandlerContext, @Nonnull evt:Any):Unit = {
      WebSocketFrameHandler.logger.trace("userEventTriggered({},{})", ctx, evt)
      evt match {
        case _:WebSocketServerProtocolHandler.HandshakeComplete =>
          WebSocketFrameHandler.logger.trace("server handshake complete")
          handshakeState = ClientHandshakeStateEvent.HANDSHAKE_COMPLETE
          ctx.pipeline.remove(classOf[WebSocket.HttpRequestHandler])
          servant.wsReady(ctx)
        case ClientHandshakeStateEvent.HANDSHAKE_COMPLETE =>
          WebSocketFrameHandler.logger.trace("client handshake complete")
          handshakeState = ClientHandshakeStateEvent.HANDSHAKE_COMPLETE
          servant.wsReady(ctx)
        case ClientHandshakeStateEvent.HANDSHAKE_ISSUED =>
          // HANDSHAKE_ISSUED の後に HANDSHAKE_COMPLETE が来るためこの時点ではエラーではない
          WebSocketFrameHandler.logger.trace("client handshake uncompleted")
          handshakeState = WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_ISSUED
        case _ =>
          super.userEventTriggered(ctx, evt)
      }
    }

    override protected def channelRead0(@Nonnull ctx:ChannelHandlerContext, @Nonnull msg:WebSocketFrame):Unit = {
      WebSocketFrameHandler.logger.trace("channelRead0({},{})", ctx, msg)
      if(WebSocketFrameHandler.logger.isTraceEnabled) {
        msg match {
          case frame:TextWebSocketFrame =>
            WebSocketFrameHandler.logger.trace("RECEIVE: {}", Debug.toString(frame.text))
          case _ =>
            val buf = msg.content
            WebSocketFrameHandler.logger.trace("RECEIVE: {}", Debug.toString(ByteBufUtil.getBytes(buf)))
        }
      }
      servant.wsFrameReceived(ctx, msg)
    }
  }

  /**
    * WebSocket ハンドシェイクの前段階で WebSocket 用の HTTP リクエストパスのみを許可するハンドラ。ハンドシェイク後は
    * パイプラインから取り除かれる。
    */
  private class HttpRequestHandler private(val path:String) extends SimpleChannelInboundHandler[FullHttpRequest] {
    override def channelRead0(@Nonnull ctx:ChannelHandlerContext, @Nonnull request:FullHttpRequest):Unit = {
      if(path.equalsIgnoreCase(request.uri)) {
        ctx.fireChannelRead(request.retain)
      } else {
        val msg = "asterisque WebSocket bridge doesn't work for the specified URI: " + request.uri
        ctx.writeAndFlush(HTTP.newErrorResponse(HttpResponseStatus.FORBIDDEN, msg)).addListener((_:Future[_ >: Void]) => ctx.disconnect)
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
  private def channelFutureToFuture(@Nonnull cf:ChannelFuture):Future[Channel] = {
    val promise = Promise[Channel]()
    cf.addListener { _ =>
      if(cf.isSuccess) {
        promise.success(cf.channel)
      } else {
        promise.failure(cf.cause)
      }
    }
    promise.future
  }

  /**
    * WebSocket のハンドシェイクに失敗したときに発生する例外。
    */
  class HandshakeException(@Nonnull val msg:String) extends RuntimeException(msg) {
  }

}
