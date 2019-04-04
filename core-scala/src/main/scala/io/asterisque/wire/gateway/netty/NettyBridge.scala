package io.asterisque.wire.gateway.netty

import java.io.IOException
import java.net.URI
import java.util.Objects
import java.util.concurrent.atomic.AtomicBoolean

import io.asterisque.wire.gateway.Bridge.Config
import io.asterisque.wire.gateway.{Bridge, Server, UnsupportedProtocolException, Wire}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.handler.ssl.{ClientAuth, JdkSslContext, SslContext, SslContextBuilder}
import javax.annotation.Nonnull
import javax.net.ssl.{SSLEngine, SSLException}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class NettyBridge() extends Bridge with AutoCloseable {

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
    * @param uri    接続先の URI
    * @param config 接続設定
    * @return Wire の Future
    */
  @Nonnull
  override def newWire(@Nonnull uri:URI, @Nonnull config:Config):Future[Wire] = {
    Objects.requireNonNull(uri)
    Objects.requireNonNull(config)
    getScheme(uri) match {
      case Right(secure) =>
        val wire = new WebSocketWire("C:" + uri, primary = false, config.inboundQueueSize, config.outboundQueueSize)

        SslContextBuilder
          .forClient()
          .build()

        val ssl:SslContext = if(secure) {
          Option(config.sslContext)
            /*
            SSLContext sslContext,
                         boolean isClient,
                         Iterable<String> ciphers,
                         CipherSuiteFilter cipherFilter,
                         ApplicationProtocolConfig apn,
                         ClientAuth clientAuth,
                         String[] protocols,
                         boolean startTls
             */
            .map(ctx => new JdkSslContext(ctx, true, ClientAuth.NONE).asInstanceOf[SslContext])
            .getOrElse {
              NettyBridge.logger.debug(s"ssl context ism't specified for uri $uri, use default client ssl")
              getDefaultClientSSL
            }
        } else null
        val driver = new WebSocket.Client(worker, config.subprotocol, ssl)
        driver.connect(uri, _ => wire.servant)
        wire.future
      case Left(ex) => Future.failed(ex)
    }
  }

  /**
    * @param uri      接続先の URI
    * @param config   接続設定
    * @param onAccept サーバが接続を受け付けたときのコールバック
    * @return Server の Future
    */
  @Nonnull
  override def newServer(@Nonnull uri:URI, @Nonnull config:Config, @Nonnull onAccept:Future[Wire] => Unit):Future[Server] = {
    Objects.requireNonNull(uri)
    Objects.requireNonNull(config)
    Objects.requireNonNull(onAccept)
    getScheme(uri) match {
      case Right(secure) =>
        val server = new WebSocketServer(uri)
        val ssl = if(secure) {
          Option(config.sslContext)
            .map(ctx => new JdkSslContext(ctx, false, ClientAuth.NONE).asInstanceOf[SslContext])
            .getOrElse {
              throw new NullPointerException(s"ssl context isn't specified for uri: $uri")
            }
        } else null
        val driver = new WebSocket.Server(worker, config.subprotocol, server, ssl)
        driver.bind(uri, { _ =>
          val wire = new WebSocketWire("S:" + uri, true, config.inboundQueueSize, config.outboundQueueSize)
          onAccept(wire.future)
          wire.servant
        }).map(_ => server)
      case Left(ex) => Future.failed(ex)
    }
  }

  def close():Unit = if(closing.compareAndSet(false, true)) {
    NettyBridge.logger.trace("close()")
    worker.shutdownGracefully()
  }

  @Nonnull
  private def getScheme(@Nonnull uri:URI):Either[Throwable, Boolean] = if(closing.get) {
    Left(new IOException("bridge has been closed"))
  } else {
    Option(uri.getScheme).map(_.toLowerCase) match {
      case Some("ws") => Right(false)
      case Some("wss") => Right(true)
      case None => Left(new IllegalArgumentException(s"uri scheme was not specified: $uri"))
      case _ => Left(new UnsupportedProtocolException(s"unsupported uri scheme: $uri"))
    }
  }

  @Nonnull
  private[this] def getDefaultClientSSL:SslContext = try {
    SslContextBuilder.forClient.build
  } catch {
    case ex:SSLException =>
      throw new IllegalStateException("default client ssl/tls is not available", ex)
  }

  @Nonnull
  private[this] def worker:NioEventLoopGroup = {
    if(closing.get) {
      throw new IllegalStateException("netty bridge has been wsClosed")
    }
    _worker
  }
}

object NettyBridge {
  private[NettyBridge] val logger = LoggerFactory.getLogger(classOf[NettyBridge])

  private[netty] def dump(context:SslContext):Unit = if(context != null && logger.isDebugEnabled) {
    val prefix = if(context.isServer) "SERVER" else if(context.isClient) "CLIENT" else "UNKNOWN"
    logger.debug(f"$prefix: Cipher Suites: ${context.cipherSuites().asScala.sorted.mkString(", ")}")
    logger.debug(f"$prefix: Session Cache Size: ${context.sessionCacheSize()}%,d bytes")
    logger.debug(f"$prefix: Session Timeout: ${context.sessionTimeout()}%,d seconds")
  }

  private[netty] def dump(engine:SSLEngine):Unit = if(engine != null && logger.isDebugEnabled) {
  }
}
