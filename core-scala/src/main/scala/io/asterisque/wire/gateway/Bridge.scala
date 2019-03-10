package io.asterisque.wire.gateway

import java.net.URI
import java.util.ServiceLoader

import io.asterisque.wire.gateway.Bridge.Config
import javax.annotation.Nonnull
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
trait Bridge {

  /**
    * この Bridge がサポートしている URI スキームを参照します。[[io.asterisque.wire.gateway.Bridge.Builder.newWire()]] は
    * SPI を使用して Bridge インスタンスをロードし、URI スキームに対応している Bridge を選択します。URI スキームの比較に
    * 大文字小文字は区別されません。
    *
    * @return この Bridge がサポートする URI スキーム
    */
  def supportedURISchemes:Set[String]

  /**
    * 指定されたリモートノードに対して非同期接続を行い [[Wire]] の Future を返します。
    * ソケットオプションのようなプロトコル固有のオプションは URI のクエリーで指定することができます。
    *
    * @param uri    接続先の URI
    * @param config Wire 通信設定
    * @return Wire の Future
    */
  def newWire(@Nonnull uri:URI, @Nonnull config:Config):Future[Wire]

  /**
    * 指定されたネットワークからの接続を非同期で受け付ける [[Server]] の Future を返します。
    *
    * @param uri      接続先の URI
    * @param config   Wire 通信設定
    * @param onAccept サーバが接続を受け付けたとき (新規の [[Wire]] が発生したとき) のコールバック
    */
  def newServer(@Nonnull uri:URI, @Nonnull config:Config, @Nonnull onAccept:Future[Wire] => Unit):Future[Server]
}

object Bridge {
  private[this] val logger = LoggerFactory.getLogger(classOf[Bridge])

  /**
    * SPI を使用してロードした実行環境で利用できるブリッジ。
    */
  private[this] val bridges = ServiceLoader.load(classOf[Bridge]).iterator().asScala.flatMap { bridge =>
    bridge.supportedURISchemes.map(scheme => (scheme.toLowerCase, bridge))
  }.toMap

  /**
    * 指定された URI に対応するブリッジを参照します。
    *
    * @param uri ブリッジを参照する URI
    * @return 対応するブリッジ
    */
  private[this] def find(uri:URI):Option[Bridge] = if(uri.getScheme == null) {
    throw new NullPointerException(s"scheme is not specified in uri: $uri")
  } else bridges.get(uri.getScheme.toLowerCase)

  /**
    * 新しい [[Wire]] または [[Server]] を構築するためのビルダーを作成します。
    *
    * @return ビルダー
    */
  def builder():Builder = new Builder()

  /**
    * 新しい [[Wire]] または [[Server]] を生成するために [[Bridge]] 実装に渡される設定クラス。
    *
    * @param subprotocol       サブプロトコル
    * @param inboundQueueSize  受信キューサイズ
    * @param outboundQueueSize 送信キューサイズ
    * @param sslContext        SSL/TLS 通信を行う場合に使用するコンテキスト
    */
  case class Config(
                     subprotocol:String = "",
                     inboundQueueSize:Int = Short.MaxValue,
                     outboundQueueSize:Int = Short.MaxValue,
                     sslContext:SSLContext = SSLContext.getInstance("TLS")
                   )

  /**
    * 新しい [[Wire]] または [[Server]] を構築するためのヘルパークラス。
    *
    * @see [[Bridge.builder()]]
    */
  class Builder private[Bridge]() {
    private[this] var config = Config()

    /**
      * @param subprotocol asterisque 上で実装しているサブプロトコル
      */
    def subprotocol(subprotocol:String):Builder = {
      config = config.copy(subprotocol = subprotocol)
      this
    }

    def inboundQueueSize(inboundQueueSize:Int):Builder = {
      config = config.copy(inboundQueueSize = inboundQueueSize)
      this
    }

    def outboundQueueSize(outboundQueueSize:Int):Builder = {
      config = config.copy(outboundQueueSize = outboundQueueSize)
      this
    }

    /**
      * @param sslContext Secure ソケットを示す URI スキーマが指定された場合 (例えば { @code wss://}) に使用する SSL コンテキスト
      */
    def sslContext(sslContext:SSLContext):Builder = {
      config = config.copy(sslContext = sslContext)
      this
    }

    /**
      * 指定されたリモートノードに対して非同期接続を行い [[Wire]] の Future を返します。
      * ソケットオプションのような Bridge 実装依存のオプションは URI のクエリーで指定することができます。
      *
      * @param uri 接続先の URI
      * @return Wire の Future
      */
    def newWire(@Nonnull uri:URI):Future[Wire] = find(uri)
      .map(_.newWire(uri, config))
      .getOrElse(Future.failed(new UnsupportedProtocolException(s"unsupported uri scheme: $uri")))

    /**
      * 指定されたネットワークからの接続を非同期で受け付ける [[Server]] の Future を返します。
      * ソケットオプションのような Bridge 実装依存のオプションは URI のクエリーで指定することができます。
      *
      * @param uri      接続先の URI
      * @param onAccept サーバが接続を受け付けたときのコールバック
      */
    def newServer(@Nonnull uri:URI, @Nonnull onAccept:Future[Wire] => Unit):Future[Server] = find(uri)
      .map(_.newServer(uri, config, onAccept))
      .getOrElse(Future.failed(new UnsupportedProtocolException(s"unsupported uri scheme: $uri")))

  }

}