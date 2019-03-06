package io.asterisque.wire.gateway

import java.net.URI
import java.util.ServiceLoader

import javax.annotation.{Nonnull, Nullable}
import javax.net.ssl.SSLContext
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.Future

/**
  * Bridge は下層のメッセージングネットワーク実装を利用するためのインターフェース。別のノードへの接続と、別のノードからの
  * 接続受付を実装する。
  *
  * @author Takami Torao
  */
trait Bridge extends AutoCloseable {

  /**
    * この Bridge がサポートしている URI スキームを参照します。
    *
    * @return URI スキーム
    */
  def supportedURISchemes:Set[String]

  /**
    * 指定されたリモートノードに対して非同期接続を行い [[Wire]] の Future を返します。
    * ソケットオプションのようなプロトコル固有のオプションは URI のクエリーで指定することができます。
    *
    * @param uri         接続先の URI
    * @param subprotocol asterisque 上で実装しているサブプロトコル
    * @param sslContext  Secure ソケットを示す URI スキーマが指定された場合 (例えば { @code wss://}) に使用する SSL コンテキスト
    * @return Wire の Future
    */
  def newWire(@Nonnull uri:URI, @Nonnull subprotocol:String, inboundQueueSize:Int, outboundQueueSize:Int, @Nullable sslContext:SSLContext):Future[Wire]

  /**
    * 指定されたネットワークからの接続を非同期で受け付ける [[Server]] の Future を返します。
    *
    * @param uri         接続先の URI
    * @param subprotocol asterisque 上で実装しているサブプロトコル
    * @param sslContext  Secure ソケットを示す URI スキーマが指定された場合 (例えば { @code wss://}) に使用する SSL コンテキスト
    * @param onAccept    サーバが接続を受け付けたときのコールバック
    */
  def newServer(@Nonnull uri:URI, @Nonnull subprotocol:String, inboundQueueSize:Int, outboundQueueSize:Int, @Nullable sslContext:SSLContext, @Nonnull onAccept:Future[Wire] => Unit):Future[Server]
}

object Bridge {
  private[this] val logger = LoggerFactory.getLogger(classOf[Bridge])

  private[this] val bridges = ServiceLoader.load(classOf[Bridge]).iterator().asScala.flatMap { bridge =>
    bridge.supportedURISchemes.map(scheme => (scheme.toLowerCase, bridge))
  }.toMap

  /**
    * 指定されたリモートノードに対して非同期接続を行い [[Wire]] の Future を返します。
    * ソケットオプションのようなプロトコル固有のオプションは URI のクエリーで指定することができます。
    *
    * @param uri         接続先の URI
    * @param subprotocol asterisque 上で実装しているサブプロトコル
    * @param sslContext  Secure ソケットを示す URI スキーマが指定された場合 (例えば { @code wss://}) に使用する SSL コンテキスト
    * @return Wire の Future
    */
  def newWire(@Nonnull uri:URI, @Nonnull subprotocol:String, inboundQueueSize:Int, outboundQueueSize:Int, @Nullable sslContext:SSLContext):Future[Wire] = {
    find(uri)
      .map(_.newWire(uri, subprotocol, inboundQueueSize, outboundQueueSize, sslContext))
      .getOrElse(Future.failed(new UnsupportedProtocolException(s"unsupported uri scheme: $uri")))
  }

  def newWire(@Nonnull uri:URI, @Nonnull subprotocol:String, inboundQueueSize:Int, outboundQueueSize:Int):Future[Wire] = newWire(uri, subprotocol, inboundQueueSize, outboundQueueSize, null)

  def newWire(@Nonnull uri:URI, @Nonnull subprotocol:String):Future[Wire] = newWire(uri, subprotocol, Short.MaxValue, Short.MaxValue, null)

  /**
    * 指定されたネットワークからの接続を非同期で受け付ける [[Server]] の Future を返します。
    *
    * @param uri         接続先の URI
    * @param subprotocol asterisque 上で実装しているサブプロトコル
    * @param sslContext  Secure ソケットを示す URI スキーマが指定された場合 (例えば { @code wss://}) に使用する SSL コンテキスト
    * @param onAccept    サーバが接続を受け付けたときのコールバック
    */
  def newServer(@Nonnull uri:URI, @Nonnull subprotocol:String, inboundQueueSize:Int, outboundQueueSize:Int, @Nullable sslContext:SSLContext, @Nonnull onAccept:Future[Wire] => Unit):Future[Server] = {
    find(uri)
      .map(_.newServer(uri, subprotocol, inboundQueueSize, outboundQueueSize, sslContext, onAccept))
      .getOrElse(Future.failed(new UnsupportedProtocolException(s"unsupported uri scheme: $uri")))
  }

  def newServer(@Nonnull uri:URI, @Nonnull subprotocol:String, @Nullable sslContext:SSLContext, @Nonnull onAccept:Future[Wire] => Unit):Future[Server] = newServer(uri, subprotocol, Short.MaxValue, Short.MaxValue, sslContext, onAccept)

  private[this] def find(uri:URI):Option[Bridge] = {
    if(uri.getScheme == null) {
      throw new NullPointerException(s"scheme is not specified in uri: $uri")
    }
    bridges.get(uri.getScheme.toLowerCase)
  }
}