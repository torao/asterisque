package io.asterisque.wire.gateway.netty

import java.io.IOException
import java.net.{InetSocketAddress, URI}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

import io.asterisque.wire.gateway.{Bridge, Server, UnsupportedProtocolException, Wire}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.handler.ssl.{ClientAuth, JdkSslContext, SslContext, SslContextBuilder}
import javax.annotation.{Nonnull, Nullable}
import javax.net.ssl.{SSLContext, SSLException}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


object NettyBridge {
  private val logger = LoggerFactory.getLogger(classOf[NettyBridge])
}

class NettyBridge() extends Bridge {

  def supportedURISchemes:Set[String] = Set("ws", "wss")

  /**
    * この Bridge から生成される Wire で共通して使用されるイベントループです。
    */
  private[this] val _worker = new NioEventLoopGroup

  /**
    * この Bridge に終了命令が出されていることを示すブール値です。
    */
  private[this] val closing = new AtomicBoolean(false)

  /**
    * @param uri         接続先の URI
    * @param subprotocol asterisque 上で実装しているサブプロトコル
    * @param sslContext  Secure ソケットを示す URI スキーマが指定された場合 (例えば { @code wss://}) に使用する SSL コンテキスト
    * @return Wire の Future
    */
  @Nonnull
  override def newWire(@Nonnull uri:URI, @Nonnull subprotocol:String, inboundQueueSize:Int, outboundQueueSize:Int, @Nullable sslContext:SSLContext):Future[Wire] = getScheme(uri) match {
    case Right(secure) =>
      val wire = new WebSocketWire("C:" + uri, primary = false, inboundQueueSize, outboundQueueSize)
      val ssl:SslContext = if(secure) {
        Option(sslContext).map(ctx => new JdkSslContext(ctx, true, ClientAuth.NONE).asInstanceOf[SslContext]).getOrElse(getDefaultClientSSL)
      } else null
      val driver = new WebSocket.Client(worker, subprotocol, wire.servant, ssl)
      driver.connect(uri)
      wire.future
    case Left(ex) => Future.failed(ex)
  }

  /**
    * @param uri         接続先の URI
    * @param subprotocol asterisque 上で実装しているサブプロトコル
    * @param sslContext  Secure ソケットを示す URI スキーマが指定された場合 (例えば { @code wss://}) に使用する SSL コンテキスト
    * @param onAccept    サーバが接続を受け付けたときのコールバック
    * @return Server の Future
    */
  @Nonnull
  override def newServer(@Nonnull uri:URI, @Nonnull subprotocol:String, inboundQueueSize:Int, outboundQueueSize:Int, @Nullable sslContext:SSLContext, @Nonnull onAccept:Consumer[Future[Wire]]):Future[Server] = getScheme(uri) match {
    case Right(secure) =>
      val server = new WebSocketServer
      val ssl = if(secure) {
        Option(sslContext).map(ctx => new JdkSslContext(ctx, false, ClientAuth.NONE).asInstanceOf[SslContext]).getOrElse(throw new NullPointerException("ssl context isn't specified"))
      } else null
      val path = Option(uri.getPath).filter((p:String) => p.length != 0).getOrElse("/")
      val driver = new WebSocket.Server(worker, subprotocol, path, server, ssl)
      driver.bind(uriToSocketAddress(uri), { _ =>
        val wire = new WebSocketWire("S:" + uri, true, inboundQueueSize, outboundQueueSize)
        onAccept.accept(wire.future)
        wire.servant
      }).map(_ => server)
    case Left(ex) => Future.failed(ex)
  }

  override def close():Unit = if(closing.compareAndSet(false, true)) {
    NettyBridge.logger.trace("close()")
    worker.shutdownGracefully()
  }

  @Nonnull
  private def getScheme(@Nonnull uri:URI):Either[Throwable, Boolean] = if(closing.get) {
    Left(new IOException("bridge has been closed"))
  } else {
    val scheme = uri.getScheme
    scheme.toLowerCase match {
      case null => Left(new IllegalArgumentException(s"uri scheme was not specified: $uri"))
      case "ws" => Right(false)
      case "wss" => Right(true)
      case _ => Left(new UnsupportedProtocolException(s"unsupported uri scheme: $uri"))
    }
  }

  @Nonnull
  private def getDefaultClientSSL = try {
    SslContextBuilder.forClient.build
  } catch {
    case ex:SSLException =>
      throw new IllegalStateException("default client ssl/tls is not available", ex)
  }

  @Nonnull
  private def worker = {
    if(closing.get) {
      throw new IllegalStateException("netty bridge has been wsClosed")
    }
    _worker
  }

  @Nonnull
  private def uriToSocketAddress(@Nonnull uri:URI):InetSocketAddress = {
    if(uri.getHost == null || uri.getHost.length == 0) {
      return new InetSocketAddress(uri.getPort)
    }
    new InetSocketAddress(uri.getHost, uri.getPort)
  }
}