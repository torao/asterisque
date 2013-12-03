/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import scala.concurrent.{Future, Promise}
import scala.collection._
import javax.net.ssl._
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import java.io._
import java.security.cert.{CertificateException, X509Certificate}
import java.security.KeyStore
import scala.Some

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Wire
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * [[com.kazzla.asterisk.Message]] のシリアライズ/デシリアライズを行い通信経路に転送するクラスです。
 * @author Takami Torao
 */
trait Wire extends Closeable {
	import Wire._

	/**
	 * この `Wire` がメッセージ受信通知を開始しているかどうかを表すフラグ。
	 */
	private[this] val active = new AtomicBoolean(false)

	/**
	 * この `Wire` が既にクローズされているかを表すフラグ。
	 */
	private[this] val closed = new AtomicBoolean(false)

	/**
	 * この `Wire` が開始されていない状態でサブクラスからメッセージ到着の通知があった場合に、内部的にメッセージを
	 * 保持するためのバッファです。このバッファに保持されたメッセージは `start()` の呼び出しですべてのイベント
	 * ハンドラへ通知されます。
	 */
	private[this] val buffer = new mutable.ArrayBuffer[Message]()

	/**
	 * この `Wire` がメッセージを受信したときに通知を行うイベントハンドラです。メッセージの受信通知は `start()` が
	 * 呼び出されてから `stop()` が呼び出されるまのオンライン状態で行われます。オフライン中に受信したメッセージは
	 * `Wire` 内部にバッファリングされ次回 `start()` が呼び出されたときに通知されます。
	 */
	val onReceive = new EventHandlers[Message]()

	/**
	 * この `Wire` がクローズされたときに通知を行うイベントハンドラです。
	 */
	val onClosed = new EventHandlers[Wire]()

	/**
	 * この `Wire` の表すエンドポイントがサーバ側の場合に true を返します。
	 * このフラグは通信相手との合意なしにユニークな ID を発行できるようにするために使用されます。例えば新しいパイプを
	 * 開くときの ID の最上位ビットを立てるかどうかに使用することで相手との合意なしにユニークなパイプ ID 発行を行って
	 * います。この取り決めから通信の双方でこの値が異なっている必要があります。
	 * @return サーバ側の場合 true
	 */
	def isServer:Boolean

	/**
	 * @return この `Wire` がクローズされているときに true
	 */
	def isClosed:Boolean = closed.get()

	/**
	 * @return この `Wire` がメッセージ受信通知を行っているとき true
	 */
	def isActive:Boolean = active.get()

	/**
	 * @return 通信相手を人が識別するための文字列
	 */
	def peerName:String = ""

	/**
	 * @return 認証された通信相手の SSL セッション
	 */
	def tls:Future[Option[SSLSession]] = Promise.successful(None).future

	// ==============================================================================================
	// メッセージの送信
	// ==============================================================================================
	/**
	 * 指定されたメッセージを送信します。
	 * @param msg 送信するメッセージ
	 * @throws java.io.IOException この `Wire` が既にクローズされている場合
	 */
	def send(msg:Message):Unit

	// ==============================================================================================
	// メッセージの受信
	// ==============================================================================================
	/**
	 * 下層のネットワーク実装からメッセージを受信したときに呼び出します。
	 * @param msg 受信したメッセージ
	 */
	protected def receive(msg:Message):Unit = if(! isClosed){
		if(active.get()) {
			onReceive(msg)
		} else buffer.synchronized {
			buffer.append(msg)
			logger.debug(s"message buffered on deactive wire: $msg")
		}
	} else {
		logger.debug(s"message disposed on closed wire: $msg")
	}

	// ==============================================================================================
	// メッセージ受信通知の開始
	// ==============================================================================================
	/**
	 * この `Wire` 上で受信したメッセージの `onReceive` イベントハンドラへの配信を開始します。
	 * 下層の非同期 I/O 実装が `Wire` の構築と同時にメッセージ受信を開始する場合に、イベントハンドラが正しく設定さ
	 * れるまで受信したメッセージをバッファリングする事を目的としています。
	 */
	def start():Unit = if(active.compareAndSet(false, true)){
		buffer.foreach{ msg => onReceive(msg) }
		buffer.clear()
	}

	// ==============================================================================================
	// メッセージ受信通知の停止
	// ==============================================================================================
	/**
	 * この `Wire` 上でのメッセージ配信を停止します。
	 * 停止中に受信したメッセージは内部のバッファに保持され次回開始したときに通知されます。
	 */
	def stop():Unit = if(active.compareAndSet(true, false)){
		/* */
	}

	// ==============================================================================================
	// クローズ
	// ==============================================================================================
	/**
	 * この `Wire` をクローズします。インスタンスに対して最初に呼び出されたタイミングで `onClosed` イベント
	 * ハンドラへのコールバックが行われます。このメソッドを複数回呼び出しても二度目以降は無視されます。
	 */
	def close():Unit = if(closed.compareAndSet(false, true)){
		onClosed(this)
	}
}

object Wire {
	import java.security._
	private[Wire] val logger = LoggerFactory.getLogger(classOf[Wire])

	// ==============================================================================================
	// パイプの作成
	// ==============================================================================================
	/**
	 * [[com.kazzla.asterisk.codec.Codec]] を伴わないでメッセージをやり取りするパイプ型 Wire を構築します。
	 * @return パイプ Wire の双方のタプル
	 */
	def newPipe():(Wire, Wire) = {
		import scala.language.reflectiveCalls
		val w1 = new Wire {
			var f:(Message)=>Unit = null
			val isServer = false
			def send(m:Message) = if(isClosed){
				throw new IOException("pipe closed")
			} else { f(m) }
		}
		lazy val w2 = new Wire {
			val isServer = ! w1.isServer
			def send(m:Message) = if(isClosed){
				throw new IOException("pipe closed")
			} else { w1.receive(m) }
		}
		w1.f = { m => w2.receive(m) }
		w1.onClosed ++ { _ => w2.close() }
		w2.onClosed ++ { _ => w1.close() }
		(w1, w2)
	}

	// ==============================================================================================
	// 証明書のロード
	// ==============================================================================================
	/**
	 * 指定された証明書ストアを読み込んで SSL コンテキストを作成するためのユーティリティです。
	 *
	 * TrustManager を変更する場合はシステムプロパティ `javax.net.ssl.trustStore` を使用してください。
	 * http://docs.oracle.com/javase/jp/6/technotes/guides/security/jsse/JSSERefGuide.html#TrustManagerFactory
	 *
	 * @param cert 証明書のキーストアファイル
	 * @param ksPassword 証明書キーストアのパスワード
	 * @param pkPassword 秘密鍵のパスワード
	 * @param trust 信頼済み CA 証明書のキーストアファイル
	 * @param trustPassword 信頼済み CA 証明書キーストアのパスワード
	 */
	def loadSSLContext(cert:File, ksPassword:String, pkPassword:String, trust:File, trustPassword:String):SSLContext = {

		val algorithm = Option(Security.getProperty("ssl.KeyManagerFactory.algorithm")).getOrElse("SunX509")
		val targetKeyStore = loadKeyStore(cert, ksPassword)
		val kmf = KeyManagerFactory.getInstance(algorithm)
		kmf.init(targetKeyStore, pkPassword.toCharArray)

		val trustKeyStore = loadKeyStore(trust, trustPassword)
		val tmf = TrustManagerFactory.getInstance("SunX509")
		tmf.init(trustKeyStore)

		val context = SSLContext.getInstance("TLS")
		context.init(kmf.getKeyManagers, tmf.getTrustManagers, null)
		context
	}


	// ==============================================================================================
	// キーストアの読み込み
	// ==============================================================================================
	/**
	 * 指定されたファイルからキーストアを読み込むためのユーティリティ関数です。
	 *
	 * @param file JSK 形式の KeyStore ファイル
	 * @param ksPassword KeyStore のパスワード
	 * @param ksType キーストアのタイプ
	 */
	private[this] def loadKeyStore(file:File, ksPassword:String, ksType:String = "JKS"):KeyStore = using(new BufferedInputStream(new FileInputStream(file))){ in =>
		val keyStore = KeyStore.getInstance(ksType)
		keyStore.load(in, ksPassword.toCharArray)
		keyStore
	}

}

object DummyTrustManager extends X509TrustManager {
	private[this] val logger = LoggerFactory.getLogger(this.getClass)
	def getAcceptedIssuers:Array[X509Certificate] = {
		logger.debug(s"DummyTrustManager.getAcceptedIssuers")
		Array()
	}

	def checkClientTrusted(certs:Array[X509Certificate], authType:String) = {
		logger.debug(s"DummyTrustManager.checkClientTrusted(${certs.mkString("[",",","]")},$authType)")
	}

	def checkServerTrusted(certs:Array[X509Certificate], authType:String) = {
		logger.debug(s"DummyTrustManager.checkServerTrusted(${certs.mkString("[",",","]")},$authType)")
	}

}

class TrustManager extends X509TrustManager {

	private[this] val defaultTrustManager = {
		val ks = KeyStore.getInstance("JKS")
		ks.load(new FileInputStream("trustedCerts"), "passphrase".toCharArray)

		val tmf = TrustManagerFactory.getInstance("PKIX")
		tmf.init(ks)
		tmf.getTrustManagers.find{ _.isInstanceOf[X509TrustManager] } match {
			case Some(tm) => tm.asInstanceOf[X509TrustManager]
			case None => throw new IllegalStateException("x509 certificate not found on default TrustManager")
		}
	}

	def checkClientTrusted(chain:Array[X509Certificate], authType:String):Unit = try {
		defaultTrustManager.checkClientTrusted(chain, authType)
	} catch {
		case ex:CertificateException =>
	}

	def checkServerTrusted(chain:Array[X509Certificate], authType:String):Unit = try {
		defaultTrustManager.checkServerTrusted(chain, authType)
	} catch {
		case ex:CertificateException =>
	}

	def getAcceptedIssuers:Array[X509Certificate] = {
		defaultTrustManager.getAcceptedIssuers
	}

}
