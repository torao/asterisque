package io.asterisque.wire.gateway.netty

import java.net.{InetSocketAddress, URI}
import java.util.Objects

import io.asterisque.core.wire.netty.HTTP
import io.asterisque.utils.Debug
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.buffer.ByteBufUtil
import io.netty.channel._
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler.ClientHandshakeStateEvent
import io.netty.handler.codec.http.websocketx._
import io.netty.handler.ssl.{SslContext, SslContextBuilder, SslHandler}
import io.netty.handler.stream.ChunkedWriteHandler
import javax.annotation.{Nonnull, Nullable}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

/**
  * Netty を使用した WebSocket 通信のためのライブラリ。
  */
object WebSocket {
  private[this] val SUBPROTOCOL = "v10.asterisque"

  /**
    * WebSocket プロトコルを使用するサーバクラス。
    */
  object Server {
    private[Server] val logger = LoggerFactory.getLogger(classOf[WebSocket.Server])

    trait Listener {
      def wsServerReady(@Nonnull ch:Channel):Unit = {
        logger.debug(s"wsServerReady($ch)")
      }

      /**
        * @param ctx コンテキスト。bind に失敗した場合などコンテキストが確定する前のエラーでは null
        * @param ex  発生した例外
        */
      def wsServerCaughtException(@Nullable ctx:ChannelHandlerContext, @Nonnull ex:Throwable):Unit = {
        logger.debug(s"wsServerCaughtException($ctx, $ex)")
      }
    }

    /**
      * WebSocket サーバが接続を受け付けたときにチャネルの初期化を行います。
      */
    private[Server] class Initializer(val subprotocol:String, val uri:URI, val sslContext:SslContext, val onAccept:Channel => WebSocket.Servant) extends ChannelInitializer[Channel] {
      override protected def initChannel(@Nonnull ch:Channel):Unit = {
        logger.trace("initChannel({})", ch)

        val path = Option(uri.getPath).filter(_.nonEmpty).getOrElse("/")
        val subprotocol = SUBPROTOCOL + "," + this.subprotocol
        if(validAndSecureScheme(uri)) {
          val engine = sslContext.newEngine(ch.alloc)
          ch.pipeline.addFirst("tls", new SslHandler(engine))
        }
        ch.pipeline
          .addLast("http", new HttpServerCodec)
          .addLast("http-chunked", new ChunkedWriteHandler)
          .addLast("aggregator", new HttpObjectAggregator(64 * 1024))
          .addLast("request", new WebSocket.HttpRequestHandler(path))
          .addLast("websocket", new WebSocketServerProtocolHandler(path, subprotocol))
          .addLast("io.asterisque.server", new WebSocket.WebSocketFrameHandler(onAccept(ch)))
      }
    }

  }

  /**
    * WebSocket プロトコルを使用するサーバクラス。
    *
    * @param group       サーバで使用するイベントループ
    * @param subprotocol asterisque 上で使用するサブプロトコル
    * @param listener    サーバイベントリスナ
    * @param sslContext  SSL/TLS を使用する場合は有効な { @link SslContext}、使用しない場合は null
    */
  class Server(val group:EventLoopGroup, val subprotocol:String, val listener:Server.Listener, val sslContext:SslContext) {
    private[this] val logger = Server.logger

    private var channel:Channel = _

    /**
      * 指定されたソケットアドレスにサーバソケットを bind します。bind に成功した場合 [[Server.Listener.wsServerReady()]]
      * コールバックが発生します。
      * bind に失敗した場合は例外の Future を返します ([[Server.Listener]] へのコールバックではありません)。
      *
      * @param uri      バインドアドレスとパスの URI 表現 (e.g., "wss://0.0.0.0:9999/ws")。
      * @param onAccept accept ごとに呼び出されチャネルから Servant を生成する
      * @return サーバソケットのチャネル
      */
    def bind(@Nonnull uri:URI, @Nonnull onAccept:Channel => WebSocket.Servant):Future[Channel] = {
      logger.trace("bind({})", uri)

      val port = math.max(0, uri.getPort)
      val host = Option(uri.getHost).getOrElse("localhost")
      val address = new InetSocketAddress(host, port)

      if(sslContext == null && validAndSecureScheme(uri)) {
        throw new IllegalArgumentException(s"secure WebSocket specified but ssl context is null: $uri")
      }

      val bootstrap = new ServerBootstrap()
        .group(group)
        .channel(classOf[NioServerSocketChannel])
        .handler(new ChannelInitializer[Channel]() {
          override protected def initChannel(ch:Channel):Unit = {
            listener.wsServerReady(ch)
            // super.initChannel(ch) is an abstract method
          }

          override def exceptionCaught(ctx:ChannelHandlerContext, cause:Throwable):Unit = {
            listener.wsServerCaughtException(ctx, cause)
            super.exceptionCaught(ctx, cause)
          }
        })
        .childHandler(new Server.Initializer(subprotocol, uri, sslContext, onAccept))

      bootstrap.bind(address).toFuture.andThen {
        case Success(ch) =>
          channel = ch
          logger.debug("the server has completed binding: {}", channel)
        case Failure(ex) =>
          listener.wsServerCaughtException(null, ex)
      }
    }

    def destroy():Unit = {
      logger.trace("destroy()")
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

    private[this] val DEFAULT_SSL_CONTEXT = SslContextBuilder.forClient().build()

    /**
      * WebSocket サーバと接続したときにチャネルの初期化を行います。
      */
    private[Client] class Initializer(val uri:URI, val subprotocol:String, val servant:Channel => Servant, val sslContext:SslContext) extends ChannelInitializer[Channel] {
      override protected def initChannel(@Nonnull ch:Channel):Unit = {
        logger.trace("initChannel({})", ch)

        val subprotocol = s"$SUBPROTOCOL, ${this.subprotocol}"
        if(validAndSecureScheme(uri)) {
          val engine = (if(sslContext != null) sslContext else DEFAULT_SSL_CONTEXT).newEngine(ch.alloc)
          ch.pipeline.addFirst("tls", new SslHandler(engine))
        }
        ch.pipeline
          .addLast("http", new HttpClientCodec)
          .addLast("http-chunked", new ChunkedWriteHandler)
          .addLast("aggregator", new HttpObjectAggregator(64 * 1024))
          .addLast("websocket", new WebSocketClientProtocolHandler(uri, WebSocketVersion.V13, subprotocol, false, EmptyHttpHeaders.INSTANCE, 64 * 1024))
          .addLast("io.asterisque.client", new WebSocket.WebSocketFrameHandler(servant(ch)))
      }
    }

  }

  /**
    * @param group       クライアントで使用するイベントループ
    * @param subprotocol asterisque 上で使用するサブプロトコル
    * @param sslContext  SSL/TLS を使用する場合は有効な { @link SslContext}、使用しない場合は null
    */
  class Client(@Nonnull val group:EventLoopGroup, @Nonnull val subprotocol:String, @Nullable val sslContext:SslContext) {
    private[this] val logger = Client.logger
    Objects.requireNonNull(group)
    Objects.requireNonNull(subprotocol)

    private var channel:Channel = _

    def connect(@Nonnull uri:URI, @Nonnull servant:Channel => Servant):Future[Channel] = {
      Objects.requireNonNull(uri)
      Objects.requireNonNull(servant)
      logger.trace("connect({})", uri)
      new Bootstrap()
        .group(group)
        .channel(classOf[NioSocketChannel])
        .handler(new Client.Initializer(uri, subprotocol, servant, sslContext))
        .connect(new InetSocketAddress(uri.getHost, uri.getPort))
        .toFuture.andThen {
        case Success(ch) =>
          channel = ch
          logger.debug("the client has completed binding: {}", channel)
        case Failure(_) => ()
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
  private[this] object WebSocketFrameHandler {
    private val logger = LoggerFactory.getLogger(classOf[WebSocket.WebSocketFrameHandler])
  }

  private class WebSocketFrameHandler(val servant:WebSocket.Servant) extends SimpleChannelInboundHandler[WebSocketFrame] {
    private[this] val logger = WebSocketFrameHandler.logger
    private var handshakeState:ClientHandshakeStateEvent = _

    @throws[Exception]
    override def channelActive(ctx:ChannelHandlerContext):Unit = {
      logger.trace("channelActive({})", ctx)
      super.channelActive(ctx)
    }

    @throws[Exception]
    override def channelInactive(ctx:ChannelHandlerContext):Unit = {
      logger.trace("channelInactive({})", ctx)
      if(handshakeState == ClientHandshakeStateEvent.HANDSHAKE_ISSUED) {
        servant.wsCaughtException(ctx, new WebSocket.HandshakeException("WebSocket handshake failure"))
      }
      servant.wsClosed(ctx)
      super.channelInactive(ctx)
    }

    @throws[Exception]
    override def exceptionCaught(ctx:ChannelHandlerContext, cause:Throwable):Unit = {
      logger.trace(s"exceptionCaught($ctx, $cause)")
      servant.wsCaughtException(ctx, cause)
      super.exceptionCaught(ctx, cause)
    }

    @throws[Exception]
    override def userEventTriggered(@Nonnull ctx:ChannelHandlerContext, @Nonnull evt:Any):Unit = {
      logger.trace("userEventTriggered({},{})", ctx, evt)
      evt match {
        case _:WebSocketServerProtocolHandler.HandshakeComplete =>
          logger.trace("server handshake complete")
          handshakeState = ClientHandshakeStateEvent.HANDSHAKE_COMPLETE
          ctx.pipeline.remove(classOf[WebSocket.HttpRequestHandler])
          servant.wsReady(ctx)
        case ClientHandshakeStateEvent.HANDSHAKE_COMPLETE =>
          logger.trace("client handshake complete")
          handshakeState = ClientHandshakeStateEvent.HANDSHAKE_COMPLETE
          servant.wsReady(ctx)
        case ClientHandshakeStateEvent.HANDSHAKE_ISSUED =>
          // HANDSHAKE_ISSUED の後に HANDSHAKE_COMPLETE が来るためこの時点ではエラーではない
          logger.trace("client handshake uncompleted")
          handshakeState = WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_ISSUED
        case _ =>
          super.userEventTriggered(ctx, evt)
      }
    }

    override protected def channelRead0(@Nonnull ctx:ChannelHandlerContext, @Nonnull msg:WebSocketFrame):Unit = {
      logger.trace(s"channelRead0($ctx, $msg)")
      if(logger.isTraceEnabled) {
        msg match {
          case frame:TextWebSocketFrame =>
            logger.trace("RECEIVE: {}", Debug.toString(frame.text))
          case _ =>
            val buf = msg.content
            logger.trace("RECEIVE: {}", Debug.toString(ByteBufUtil.getBytes(buf)))
        }
      }
      servant.wsFrameReceived(ctx, msg)
    }
  }

  /**
    * WebSocket ハンドシェイクの前段階で WebSocket 用の HTTP リクエストパスのみを許可するハンドラ。ハンドシェイク後は
    * パイプラインから取り除かれる。
    */
  private class HttpRequestHandler(val path:String) extends SimpleChannelInboundHandler[FullHttpRequest] {
    private[this] val logger = LoggerFactory.getLogger(classOf[HttpRequestHandler])

    override def channelRead0(@Nonnull ctx:ChannelHandlerContext, @Nonnull request:FullHttpRequest):Unit = {
      if(path.equalsIgnoreCase(request.uri)) {
        ctx.fireChannelRead(request.retain)
      } else {
        val msg = s"asterisque WebSocket bridge doesn't work for the specified URI: ${request.uri}"
        ctx.writeAndFlush(HTTP.newErrorResponse(HttpResponseStatus.FORBIDDEN, msg))
          .addListener { _:io.netty.util.concurrent.Future[_ >: Void] => ctx.disconnect() }
        logger.debug(msg)
      }
    }
  }

  /**
    * Netty の ChannelFuture を CompletableFuture に変換する。
    *
    * @param cf ChannelFuture
    */
  private[this] implicit class _ChannelFuture(cf:ChannelFuture) {
    def toFuture:Future[Channel] = {
      val promise = Promise[Channel]()
      cf.addListener { _:io.netty.util.concurrent.Future[_] =>
        if(cf.isSuccess) {
          promise.success(cf.channel)
        } else {
          promise.failure(cf.cause)
        }
      }
      promise.future
    }
  }

  /**
    * 指定された URI スキームがセキュア WebSocket かアンセキュア WebSocket かを判定します。
    *
    * @param uri セキュアを判定する URI
    * @throws IllegalArgumentException URI スキームを認識できない場合
    * @return セキュア WebSocket の場合 true
    */
  @throws[IllegalArgumentException]
  private[this] def validAndSecureScheme(uri:URI):Boolean = {
    Option(uri.getScheme).map(_.toLowerCase).getOrElse("wss") match {
      case "ws" => false
      case "wss" => true
      case scheme =>
        throw new IllegalArgumentException(s"unsupported WebSocket scheme '$scheme': $uri")
    }
  }

  /**
    * WebSocket のハンドシェイクに失敗したときに発生する例外。
    */
  class HandshakeException(@Nonnull val msg:String) extends RuntimeException(msg)

}
