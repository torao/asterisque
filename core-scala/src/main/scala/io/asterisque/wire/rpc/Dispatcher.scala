package io.asterisque.wire.rpc

import java.security.cert.X509Certificate
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import io.asterisque.wire.gateway.{MessageQueue, Wire}
import io.asterisque.wire.message.Message.Control
import io.asterisque.wire.message.SyncSession
import io.asterisque.wire.rpc.Dispatcher._
import io.asterisque.wire.{AuthenticationException, ProtocolException}
import javax.annotation.{Nonnull, Nullable}
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Random

/**
  * 複数のサービスを保持し、リモートからのセッション開始要求に応える
  *
  * @param context ディスパッチャーがリクエストを処理するためのスレッドプール
  */
class Dispatcher private[rpc](val context:ExecutionContext, codec:Codec) {

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

  def bind(@Nonnull wire:Wire, conf:Map[String, String]):Future[Session] = {

    // TLS 証明書でのピア認証が必要な場合は TLS Session の中から信頼済み証明書を参照
    val validCertificates:Seq[X509Certificate] = wire.session.map { tls =>
      // NOTE TLS で TrustManager による認証が通過しているためこの証明書パスは信頼可能
      tls.getPeerCertificates.map(_.asInstanceOf[X509Certificate]).toSeq
    }.getOrElse {
      val msg = s"peer authentication is required but not by TLS"
      return Future.failed(new AuthenticationException(msg))
    }

    // セッション ID の決定
    var sessionId:Long = 0
    if(wire.isPrimary) {
      do {
        sessionId = Random.nextLong()
      } while(sessions.containsKey(sessionId))
      logger.trace("new session-id issued: {}", sessionId)
    }

    // ハンドシェイクの開始
    new Handshake(wire, conf, validCertificates).future
  }

  @Nonnull
  private def handshake(@Nonnull hs:Handshake, @Nonnull received:SyncSession):Future[Session] = {

    // 新規セッションの構築と登録
    @tailrec
    def _newSession():Session = {
      val sessionId = sessionIdSequence.getAndIncrement()
      val oldSession = sessions.computeIfAbsent(sessionId, { _:Long =>
        val pair = if(hs.wire.isPrimary) SyncSession.Pair(hs.sent, received) else SyncSession.Pair(received, hs.sent)
        val session = new Session(sessionId, this, hs.wire, codec, pair)
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
    * @param pipe  呼び出しのパイプ
    * @param logId ログ出力用の文字列
    * @return サービスの呼び出し結果
    */
  private[rpc] def dispatch(@Nonnull pipe:Pipe, @Nonnull logId:String):Future[Array[Byte]] = {
    val service = services.get(pipe.service)
    if(service == null) {
      val msg = s"no such service: ${pipe.service}"
      logger.debug(s"$logId: $msg")
      Future.failed(new ProtocolException(msg))
    } else {
      service.apply(codec, pipe, context)
    }
  }

  /**
    * メッセージキューに対して [[SyncSession]] ハンドシェイクの到着を待機するクラスです。
    *
    * @param wire SyncSession の到着を待機する Wire
    * @param conf セッション同期の設定
    */
  private[this] class Handshake(val wire:Wire, val conf:Map[String, String], val validCerts:Seq[X509Certificate]) extends MessageQueue.Listener {

    private[this] val promise = Promise[Session]()

    val sent:SyncSession = SyncSession(System.currentTimeMillis, conf)

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
