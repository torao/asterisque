package io.asterisque.wire.rpc

import java.security.cert.X509Certificate
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import io.asterisque.auth.Authority
import io.asterisque.wire.gateway.{MessageQueue, Wire}
import io.asterisque.wire.message.Message.Control
import io.asterisque.wire.message.SyncSession
import io.asterisque.wire.rpc.Dispatcher._
import io.asterisque.wire.{AuthenticationException, Envelope, ProtocolException}
import javax.annotation.{Nonnull, Nullable}
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Random

/**
  * 複数のサービスを保持し、リモートからのセッション開始要求に応える
  *
  * @param auth        ピア認証を行うための認証局
  * @param sealedCert  このノードの署名付き証明書と属性
  * @param context     ディスパッチャーがリクエストを処理するためのスレッドプール
  * @param tlsPeerAuth TLS レイヤーで交換した証明書の検証を行う場合 true
  */
class Dispatcher private[rpc](auth:Authority, sealedCert:Envelope, val context:ExecutionContext, tlsPeerAuth:Boolean = true) {

  /**
    * このディスパッチャー上でサービスの処理を行っているセッション。
    */
  private[this] val sessions = new ConcurrentHashMap[Long, Session]()

  /**
    * 新規セッションを識別するための ID を取得するためのシーケンス。
    */
  private[this] val sessionIdSequence = new AtomicLong(0)

  /**
    * このディスパッチャー上で提供するサービス。
    */
  object services {
    private[this] val services = new ConcurrentHashMap[String, Service]()

    @Nullable
    def set(@Nonnull serviceId:String, @Nonnull service:Service):Service = {
      Objects.requireNonNull(serviceId)
      Objects.requireNonNull(service)
      services.put(serviceId, service)
    }

    @Nullable
    def remove(@Nonnull serviceId:String):Service = {
      Objects.requireNonNull(serviceId)
      services.remove(serviceId)
    }

    @Nullable
    def get(@Nonnull serviceId:String):Service = {
      Objects.requireNonNull(serviceId)
      services.get(serviceId)
    }
  }

  def bind(@Nonnull wire:Wire, @Nonnull serviceId:String, conf:Map[String, String]):Future[Session] = {

    // TLS 証明書レベルでのピア認証が必要な場合は TLS Session の中から信頼済み証明書を参照
    val validCertficates:Seq[X509Certificate] = if(tlsPeerAuth) {
      wire.session match {
        case Some(tls) =>
          val certs = tls.getPeerCertificates.collect { case cert:X509Certificate => cert }.flatMap(auth.findIssuer)
          if(certs.isEmpty) {
            val msg = s"peer authentication is required but TLS doesn't contain valid certificates"
            return Future.failed(new AuthenticationException(msg))
          }
          certs
        case None =>
          val msg = s"peer authentication is required but not by TLS"
          return Future.failed(new AuthenticationException(msg))
      }
    } else Seq.empty

    // セッション ID の決定
    var sessionId:Long = 0
    if(wire.isPrimary) {
      do {
        sessionId = Random.nextLong()
      } while(sessions.containsKey(sessionId))
      logger.trace("new session-id issued: {}", sessionId)
    }

    // ハンドシェイクの開始
    new Handshake(wire, serviceId, conf, validCertficates).future
  }

  @Nonnull
  private def handshake(@Nonnull hs:Handshake, @Nonnull received:SyncSession):Future[Session] = {

    // ピアの証明書が正しく署名され有効であることを確認
    try {
      received.sealedCertificate.signer.checkValidity()
      received.sealedCertificate.verify()
      received.cert.cert.checkValidity()
    } catch {
      case ex:Exception =>
        return Future.failed(ex)
    }

    // 証明書属性の署名者が信頼できる CA であることを確認
    if(!auth.getCACertificates.contains(received.sealedCertificate.signer)) {
      val msg = s"the signer exchanged in the sync session-config isn't a trusted CA"
      return Future.failed(new AuthenticationException(msg))
    }

    // TLS レイヤーのピア証明書が信頼できる CA から発行済みであることは検証済みであるため
    // SyncSession で交換した証明書がそのいずれかと致していることを確認
    if(!hs.validCerts.contains(received.cert.cert)) {
      val msg = s"the certificate of sync session-config doesn't match the certificate exchanged with TLS"
      return Future.failed(new AuthenticationException(msg))
    }

    // ピアの証明書が信頼済み CA によって発行されていることを確認
    if(auth.findIssuer(received.cert.cert).isEmpty) {
      val msg = s"certificate of connected peer is not issued by trusted CA: ${received.cert.cert}"
      return Future.failed(new AuthenticationException(msg))
    }

    // 新規セッションの構築と登録
    @tailrec
    def _newSession():Session = {
      val sessionId = sessionIdSequence.getAndIncrement()
      val oldSession = sessions.computeIfAbsent(sessionId, { _:Long =>
        val pair = if(hs.wire.isPrimary) SyncSession.Pair(hs.sent, received) else SyncSession.Pair(received, hs.sent)
        val session = new Session(sessionId, this, hs.wire, pair)
        session.addListener { _:Session =>
          logger.debug("{}: session closed: {}", session.logId, sessionId)
          sessions.remove(sessionId)
        }
        session
      })
      if(oldSession == null) sessions.get(sessionId) else _newSession()
    }

    val newSession = _newSession()
    logger.info("{}: handshake success, beginning session", newSession.logId)
    Future.successful(newSession)
  }

  /**
    * 指定されたサービスの処理を呼び出します。
    *
    * @param serviceId 呼び出すサービスの ID
    * @param pipe      呼び出しのパイプ
    * @param logId     ログ出力用の文字列
    * @return サービスの呼び出し結果
    */
  private[rpc] def dispatch(serviceId:String, @Nonnull pipe:Pipe, @Nonnull logId:String):Future[Any] = {
    val service = services.get(serviceId)
    if(service == null) {
      val msg = s"no such service: $serviceId"
      logger.debug(s"$logId: $msg")
      Future.failed(new ProtocolException(msg))
    } else {
      service(pipe, context)
    }
  }

  /**
    * メッセージキューに対して [[SyncSession]] ハンドシェイクの到着を待機するクラスです。
    *
    * @param wire      SyncSession の到着を待機する Wire
    * @param serviceId 此方から接続しようとしているサービス
    * @param conf      セッション同期の設定
    */
  private[this] class Handshake(val wire:Wire, val serviceId:String, val conf:Map[String, String], val validCerts:Seq[X509Certificate]) extends MessageQueue.Listener {

    private[this] val promise = Promise[Session]()

    val sent = SyncSession(sealedCert, serviceId, System.currentTimeMillis, conf)

    def future:Future[Session] = promise.future

    // ハンドシェイクメッセージの受信ハンドラを設置
    wire.inbound.addListener(this)

    // ハンドシェイクメッセージの送信
    wire.outbound.offer(Control(sent))

    override def messagePollable(@Nonnull messageQueue:MessageQueue, pollable:Boolean):Unit = if(pollable) {
      // 受信メッセージの取得
      val msg = messageQueue.poll()
      if(msg != null && !promise.isCompleted) {
        messageQueue.removeListener(this)
        msg match {
          case Control(sync:SyncSession) =>
            promise.completeWith(handshake(this, sync))
          case _ =>
            val message = s"SyncSession control message expected but a different one detected: $msg"
            logger.error(message)
            promise.failure(new ProtocolException(message))
            wire.close()
        }
      }
    }
  }

}

object Dispatcher {
  private val logger = LoggerFactory.getLogger(classOf[Dispatcher])

  trait Services {
    @Nullable
    def set(@Nonnull serviceId:String, @Nonnull service:Service):Service

    @Nullable
    def remove(@Nonnull services:String):Service

    @Nullable
    def get(@Nonnull serviceId:String):Service
  }

}
