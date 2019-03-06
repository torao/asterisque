package io.asterisque.wire.rpc

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

import io.asterisque.core.util.Latch
import io.asterisque.utils.EventDispatcher
import io.asterisque.wire.ProtocolException
import io.asterisque.wire.gateway.{MessageQueue, Wire}
import io.asterisque.wire.message.Message.{Block, Close, Control, Open}
import io.asterisque.wire.message.{Abort, Message, SyncSession}
import io.asterisque.wire.rpc.Session._
import javax.annotation.Nonnull
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


/**
  * 一つの [[Wire]] 上で行われるピアとの通信状態を表すクラスです。
  *
  * ピア間の通信において役割の衝突を避ける目的で、便宜的に接続受付側をプライマリ (primary)、通信開始側をセカンダリ (secondary)
  * と定めます。
  *
  * セッションはピアとのメッセージ送受信のための [[Wire]] を持ちます (Wire を持たない状態は物理的に接続して
  * いない状態を表します)。セッションは Wire が切断されると新しい Wire の接続を試みます。
  *
  * セッション ID はサーバ側
  * セッションが構築されると [[SyncSession 状態同期]] のための
  * [[Control]] メッセージがクライアントから送信されます。サーバはこのメッセージを受け
  * ると新しいセッション ID を発行して状態同期の [[Control]] メッセージで応答し、双方の
  * セッションが開始します。
  *
  * @param id   このセッションの ID を参照します。この ID は同じノード上に存在するセッションに対してユニークです。
  * @param sync このセッションを生成するために Wire に対して送受信した状態同期メッセージ
  * @author Takami Torao
  */
class Session private[rpc](val id:Long, dispatcher:Dispatcher, wire:Wire, sync:SyncSession.Pair) extends EventDispatcher[Session.Listener] {

  if(wire.remote == null) {
    throw new IllegalStateException(s"remote address of wire has not been confirmed: $wire")
  }

  this.wire.outbound.addListener(new MessageQueue.Listener() {
    override def messageOfferable(@Nonnull messageQueue:MessageQueue, offerable:Boolean):Unit = if(offerable) {
      outboundLatch.open()
    } else {
      outboundLatch.lock()
    }
  })

  this.wire.inbound.addListener(new MessageQueue.Listener {
    override def messagePollable(@Nonnull messageQueue:MessageQueue, pollable:Boolean):Unit = if(pollable) {
      var msg = messageQueue.poll()
      while(msg != null) {
        deliverToLocal(msg)
        msg = messageQueue.poll()
      }
    }
  })

  logger.debug("{}: session created", logId)

  /**
    * このセッションがクローズ済みかを表すフラグ。
    */
  private[this] val closed = new AtomicBoolean(false)

  private[this] val pipes = new PipeSpace(this)

  /**
    * このセッションが接続しているローカル側のサービス ID。
    */
  val localServiceId:String = (if(wire.isPrimary) sync.secondary else sync.primary).serviceId

  /**
    * [[Wire.outbound]] のメッセージ流出量を調整するためのラッチです。
    */
  final private val outboundLatch = new Latch()

  /**
    * ログメッセージに付与する識別子。
    */
  private[rpc] val logId = f"${if(isPrimary) 'S' else 'C'}:$id%08X"

  /**
    * このセッションが peer に対してプライマリかを参照します。この判定値は P2P での役割を決めるために使用
    * されます。
    *
    * @return プライマリの場合 true、セカンダリの場合 false
    */
  def isPrimary:Boolean = wire.isPrimary

  /**
    * このセッションが接続している peer の IP アドレスを参照します。
    *
    * @return リモート IP アドレス
    */
  @Nonnull
  def remote:InetSocketAddress = wire.remote match {
    case null =>
      throw new IllegalStateException("remote address of wire has not been confirmed")
    case address:InetSocketAddress =>
      address
  }

  @Nonnull
  def config:SyncSession.Pair = sync

  /**
    * このセッションがクローズされているかを参照します。
    *
    * @return セッションがクローズを完了している場合 true
    */
  def isClosed:Boolean = closed.get

  /**
    * このセッションを graceful にクローズします。このメソッドは [[close() close(true)]] と等価です。
    */
  def close():Unit = close(true)

  /**
    * このセッションをクローズします。
    * セッションが使用している [[Wire]] および実行中のすべての [[Pipe]] はクローズされ、以後のメッセージ配信は
    * 行われなくなります。
    */
  def close(graceful:Boolean):Unit = if(closed.compareAndSet(false, true)) {
    logger.debug(s"$logId: this session is closing in ${if(graceful) "gracefully" else "forcibly"}")

    // 残っているすべてのパイプに Close メッセージを送信
    pipes.close(graceful)

    // パイプクローズの後に Close 制御メッセージをポスト
    if(graceful) {
      post(Control.CloseMessage)
    }

    // 以降のメッセージ送信をすべて例外に変更して送信を終了
    // ※Pipe#close() で Session#post() が呼び出されるためすべてのパイプに Close を投げた後に行う
    // Wire のクローズ
    wire.close()

    // セッションのクローズを通知
    foreach(_.sessionClosed(this))
  }

  /**
    * このセッション上のピアに対して指定された function との非同期呼び出しのためのパイプを作成します。
    *
    * @param priority           新しく生成するパイプの同一セッション内でのプライオリティ
    * @param function           function の識別子
    * @param params             function の実行パラメータ
    * @param onTransferComplete 呼び出し先とのパイプが生成されたときに実行される処理
    * @return オープンしたパイプでの処理の実行結果を通知する Future
    */
  def open(priority:Byte, function:Short, @Nonnull params:Array[Any], @Nonnull onTransferComplete:Pipe => Future[Any]):Future[Any] = {
    val pipe = pipes.create(priority, function)
    pipe.open(params)
    onTransferComplete.apply(pipe)
  }

  def open(priority:Byte, function:Short, @Nonnull params:Array[Any]):Future[Any] = {
    open(priority, function, params, _.future)
  }

  /**
    * ピアからメッセージを受信したときに呼び出されます。メッセージの種類によって処理を分岐します。
    *
    * @param msg 受信したメッセージ
    */
  private[this] def deliverToLocal(msg:Message):Unit = {
    logger.trace(s"$logId: deliver: $msg")
    implicit val _context:ExecutionContext = dispatcher.context
    msg match {
      case open:Open =>
        val pipe = pipes.create(open)
        dispatcher.dispatch(localServiceId, pipe, logId).onComplete {
          case Success(result) =>
            post(Close.withSuccessful(pipe.id, result))
          case Failure(ex:Abort) =>
            post(Close.withFailure(pipe.id, ex))
          case Failure(ex:NoSuchServiceException) =>
            post(Close.withFailure(pipe.id, Abort.ServiceUndefined, ex.getMessage))
          case Failure(ex:NoSuchFunctionException) =>
            post(Close.withFailure(pipe.id, Abort.FunctionUndefined, ex.getMessage))
          case Failure(ex) =>
            post(Close.withFailure(pipe.id, Abort.Unexpected, ex.getMessage))
        }
      case close:Close =>
        pipes.get(msg.pipeId) match {
          case Some(pipe) =>
            try {
              pipe.closePassively(close)
            } catch {
              case ex:Throwable if !ex.isInstanceOf[ThreadDeath] =>
                logger.error("{}: unexpected error: {}; in closing pipe {}", logId, msg, pipe, ex)
                post(Close.withFailure(msg.pipeId, Abort.Unexpected, "internal error"))
            }
          case None =>
            logger.debug("{}: both of sessions unknown pipe #{}", logId, msg.pipeId & 0xFFFF)
        }
      case block:Block =>
        // メッセージの配信先パイプを参照
        pipes.get(msg.pipeId) match {
          case Some(pipe:StreamPipe) =>
            try {
              // Open は受信したがサービスの処理が完了していない状態でメッセージを受信した場合にパイプのキューに保存できるよう
              // 一度パイプを経由して deliver(Pipe,Message) を実行する
              pipe.dispatchBlock(block)
            } catch {
              case ex:Throwable if !ex.isInstanceOf[ThreadDeath] =>
                logger.error("{}: unexpected error: {}, closing pipe {}", logId, msg, pipe, ex)
                post(Close.withFailure(msg.pipeId, Abort.Unexpected, "internal error"))
            }
          case Some(pipe) =>
            // ブロックの受信が宣言されていないパイプに対してはエラーでクローズする
            logger.warn(s"$logId: block reception is not permitted on this pipe: $pipe, closing")
            pipe.closeWithError(Abort.FunctionCannotReceiveBlock, f"function ${pipe.function}%d does not allow block transmission")
          case None =>
            logger.debug(s"$logId: unknown pipe-id: $msg")
            post(Close.withFailure(msg.pipeId, Abort(Abort.DestinationPipeUnreachable, f"unknown pipe-id specified: #${msg.pipeId & 0xFFFF}%04X")))
        }
      // Control メッセージはディスパッチせずこのセッション内で処理する
      case Control(Control.CloseField) =>
        close(false)
      case Control(_:SyncSession) =>
        // SYNC_SESSION はセッション構築前に処理されているはずであるためエラー
        logger.error(s"$logId: unexpected SYNC_SESSION control message received: $msg")
        throw new ProtocolException("unsupported SYNC_SESSION control message")
      case Control(_) =>
        logger.error(s"$logId: unsupported control: $msg")
        throw new ProtocolException("unsupported control message")
    }
  }

  /**
    * ピアに対して指定されたメッセージを送信します。
    */
  private[this] def post(msg:Message):Unit = if(isClosed && !msg.isInstanceOf[Control]) {
    logger.error(s"$logId: session $id has been closed, message is discarded: $msg")
  } else try {
    outboundLatch.exec(() => wire.outbound.offer(msg))
  } catch {
    case ex:InterruptedException =>
      logger.error(s"$logId: operation interrupted", ex)
  }

  private[rpc] def destroy(pipeId:Short):Unit = {
    pipes.destroy(pipeId)
  }

  /**
    * このセッションの相手側となるインターフェースを参照します。
    */
  def bind[T](clazz:Class[T]):T = bind(clazz, Thread.currentThread.getContextClassLoader)

  /**
    * このセッションのピアと通信することのできるリモートインターフェースを参照します。
    *
    * @param clazz  リモートインターフェース
    * @param loader クラスローダー
    * @tparam T リモート実装
    * @return リモートインターフェース
    */
  def bind[T](clazz:Class[T], loader:ClassLoader):T = {
    val skeleton = new Skeleton(logId, clazz, open)
    clazz.cast(java.lang.reflect.Proxy.newProxyInstance(loader, Array[Class[_]](clazz), skeleton))
  }

  override def toString:String = id.toString

  private[rpc] val stub = new Pipe.Stub() {
    @Nonnull
    override def id:String = Session.this.id.toString

    override def post(@Nonnull msg:Message):Unit = {
      Session.this.post(msg)
    }

    override def closed(@Nonnull pipe:Pipe):Unit = {
      pipes.destroy(pipe.id)
    }
  }

}

object Session {
  private val logger = LoggerFactory.getLogger(classOf[Session])

  trait Listener {
    def sessionClosed(@Nonnull session:Session):Unit
  }

}
