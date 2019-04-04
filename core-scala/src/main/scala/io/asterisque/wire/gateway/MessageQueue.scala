package io.asterisque.wire.gateway

import java.util.Objects
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import io.asterisque.wire.gateway.MessageQueue.{Listener, logger}
import io.asterisque.wire.message.Message
import io.asterisque.wire.message.Message.Control
import io.asterisque.wire.message.Message.Control.Fields
import javax.annotation.{Nonnull, Nullable}
import org.slf4j.LoggerFactory

import scala.collection.mutable


/**
  * [[Wire]] と Session の間に位置する非同期メッセージングの送受信キューです。この MessageQueue はメッセージの
  * 取り出し可能状態と受け取り可能状態を [[Listener]] を経由してそれらに通知します。これは TCP/IP レベルでの
  * back pressure を機能させることを意図しています。
  *
  * MessageQueue はサイズを持ちますが、これは超過した場合に [[Listener]] 経由で通知を行うのみであり、ブロッキングや
  * 例外を伴うものではありません。
  *
  * `cooperativeLimit` はこのキューに保存できる協調的上限サイズを表します。アプリケーションはこの数値を超えてメッセージを
  * [[offer()]] することができますが、offerable = false のコールバックが発生します。
  *
  * @param name             このキューの名前
  * @param cooperativeLimit キューの協調的上限サイズ
  */
class MessageQueue(@Nonnull val name:String, val cooperativeLimit:Int) extends AutoCloseable {
  Objects.requireNonNull(name)
  if(cooperativeLimit <= 0) {
    throw new IllegalArgumentException(s"incorrect queue size: $cooperativeLimit")
  }

  /**
    * メッセージキュー。[[take]] 時に queue のサイズ確認のための同期を行わないように、このメッセージを poll()
    * した後のサイズを併せて保存する (WAIT の発生する poll() を synchronized すると [[offer()]] と競合して
    * デッドロックする)。
    */
  private[this] val queue = new LinkedBlockingQueue[Message]

  private[this] val listeners = mutable.Buffer[Listener]()

  private[this] val _closed = new AtomicBoolean(false)

  private[this] val _iterator = new AtomicReference[MessageIterator]

  /**
    * このキューで保留しているメッセージ数を参照します。
    *
    * @return キューのメッセージ数
    */
  def size:Int = queue.size

  /**
    * このキューの状態を文字列化します。
    *
    * @return キューの文字列表現
    */
  override def toString:String = f"$name%s,size=$size%d/$cooperativeLimit%d${if(_closed.get()) ",CLOSED" else ""}%s"

  /**
    * このキューからメッセージを取り出します。指定されたタイムアウトまでにキューにメッセージが到達しなかった場合は null を
    * 返します。`timeout` に 0 以下の値をしてした場合は即座に結果を返します。
    *
    * メソッドの呼び出しでキューの空を検知した場合 [[Listener]] 経由で pollable = false が通知されます。また
    * メッセージ数がキューのサイズを下回った場合 offerable = true が通知されます。
    *
    * すでにクローズされているキューに対する poll() や、メッセージ待機中に外部スレッドからクローズされた場合、この
    * メソッドは即時に null を返します。
    *
    * @param timeout タイムアウト (0 以下の場合は即座に返す)
    * @param unit    タイムアウトで指定された数値の時間単位
    * @return キューから取り出したメッセージ、または null
    * @throws InterruptedException メッセージ待機中にスレッドが割り込まれた場合
    */
  @Nullable
  @throws[InterruptedException]
  def take(timeout:Long, @Nonnull unit:TimeUnit):Message = {

    // lock() が呼び出されていても正常に受理した分は取り出しは可能としている
    var message:Message = null
    var queueSize = 0
    queue.synchronized {
      message = queue.poll()
      if(message == null && timeout > 0) {
        queue.wait(unit.toMillis(timeout))
        message = queue.poll()
      }
      queueSize = queue.size
    }
    logger.trace("{}>> {} @ {}", name, message, Thread.currentThread.getName)

    // キューからメッセージが取り出しできなくなった
    if(queueSize == 0) {
      logger.trace("messagePollable({}, {})", this, false)
      listeners.foreach(_.messagePollable(this, pollable = false))
    }

    // キューにメッセージが追加できるようになった
    if(queueSize == this.cooperativeLimit - 1 && !closed) {
      logger.trace("messageOfferable({}, {})", this, true)
      listeners.foreach(_.messageOfferable(this, offerable = true))
    }

    // メッセージの終了を表すマーカーの場合
    if(message != null && message.eq(MessageQueue.END_OF_MESSAGE)) {
      queue.synchronized {
        // ほかに待機しているスレッドのために EOM を再投入する
        offerAndNotify(MessageQueue.END_OF_MESSAGE)
      }
      return null
    }
    message
  }

  /**
    * このキューにメッセージが到着するまで待機して取り出します。
    *
    * メソッドの呼び出しでキューの空を検知した場合 [[Listener]] 経由で pollable = false が通知されます。また
    * メッセージ数がキューのサイズを下回った場合 offerable = true が通知されます。
    *
    * すでにクローズされているキューに対する poll() や、メッセージ待機中に外部スレッドからクローズされた場合、この
    * メソッドは即時に null を返します。
    *
    * @return キューから取り出したメッセージ、または null
    * @throws InterruptedException メッセージ待機中にスレッドが割り込まれた場合
    */
  @Nonnull
  @throws[InterruptedException]
  def take():Message = take(Long.MaxValue, TimeUnit.SECONDS)

  /**
    * このキューからメッセージを取り出します。キューにメッセージが存在しない場合は即座に null を返します。
    *
    * メソッドの呼び出しでキューの空を検知した場合 [[Listener]] 経由で pollable = false が通知されます。また
    * メッセージ数がキューのサイズを下回った場合 offerable = true が通知されます。
    *
    * @return キューから取り出したメッセージ、または null
    */
  @Nullable
  def poll():Message = try {
    take(0, TimeUnit.SECONDS)
  } catch {
    case ex:InterruptedException =>
      throw new IllegalStateException("BlockingQueue.poll() without timeout is interrupted", ex)
  }

  /**
    * このキューにメッセージを追加します。この呼び出しによりメッセージ数がキューのサイズを超えた場合 [[Listener]]
    * 経由で offerable = false を通知しますが、メッセージの追加そのものは正常に完了します。また空の状態で呼び出された
    * 場合は pollable = true が通知されます。
    *
    * @param msg キューに追加するメッセージ
    * @throws IllegalStateException キューがクローズされている場合
    */
  @throws[IllegalStateException]
  def offer(@Nonnull msg:Message):Unit = {

    if(msg.eq(MessageQueue.END_OF_MESSAGE)) {
      logger.debug("closing queue by end-of-message offer")
      close()
      return
    }

    var queueSize = 0
    queue.synchronized {
      if(closed) {
        throw new IllegalStateException("message queue has been closed")
      }
      queueSize = offerAndNotify(msg)
    }

    logger.trace("{}<< {} @ {}", name, msg, Thread.currentThread.getName)

    // コールバック先で別のキュー操作を行う事ができるようにクリティカルセクションの外でリスナを呼び出す
    if(queueSize == 1) {
      logger.trace("messagePollable({}, {})", this, true)
      listeners.foreach(_.messagePollable(this, pollable = true))
    }
    if(queueSize >= this.cooperativeLimit) {
      logger.trace("messageOfferable({}, {})", this, false)
      listeners.foreach(_.messageOfferable(this, offerable = false))
    }
  }

  /**
    * 指定されたメッセージをキューに追加しメッセージを待機しているスレッドに通知します。このメソッドの呼び出しは
    * [[MessageQueue.queue]] に対するモニターを獲得している必要があります。
    *
    * @param msg キューに追加するメッセージ
    * @return 追加後のキューのサイズ
    */
  private[this] def offerAndNotify(@Nonnull msg:Message):Int = {
    assert(Thread.holdsLock(queue))
    queue.offer(msg)
    queue.notify() // キューを待機しているスレッドに通知
    queue.size
  }

  /**
    * このキューがクローズされているかを参照します。
    *
    * @return クローズされている場合 true
    */
  def closed:Boolean = _closed.get

  /**
    * このキューをクローズします。この操作によりキューは新しいメッセージの [[MessageQueue.offer()]] を受け付けなくなります。
    * ただしキューに保存されているメッセージの取り出しは可能です。
    */
  override def close():Unit = queue.synchronized {
    if(_closed.compareAndSet(false, true)) {
      val queueSize = offerAndNotify(MessageQueue.END_OF_MESSAGE)
      MessageQueue.logger.trace("lock(), {} messages remain", queueSize - 1)
    }
  }

  /**
    * キューから取り出せるメッセージを同期処理で扱うための列挙 [[Iterator]] を参照します。このメソッドは [[take()]]
    * の同期版代替として利用することができます。
    *
    * @return メッセージの iterator
    */
  def iterator:Iterator[Message] = {
    _iterator.compareAndSet(null, new MessageIterator(this))
    _iterator.get
  }

  /**
    * 指定された Listener をこの MessageQueue に追加します。このメソッドの呼び出しにより listener には現在のキューの状況が
    * 通知されます。
    *
    * @param listener 追加するリスナ
    */
  def addListener(@Nonnull listener:Listener):Unit = listeners.synchronized {
    listeners.append(listener)
    val size = queue.size
    listener.messagePollable(this, size > 0)
    listener.messageOfferable(this, size < cooperativeLimit)
  }

  /**
    * 指定された Listener をこの MessageQueue から削除します。
    *
    * @param listener 削除するリスナ
    */
  def removeListener(@Nonnull listener:Listener):Unit = listeners.synchronized {
    listeners.indexOf(listener) match {
      case i if i >= 0 =>
        listeners.remove(i)
      case _ =>
        logger.warn(s"the specified listener is not registered: $listener")
    }
  }
}

object MessageQueue {
  private[MessageQueue] val logger = LoggerFactory.getLogger(classOf[MessageQueue])

  /**
    * [[MessageQueue]] のメッセージ送受信の準備状態に変更があったときに通知を受けるための Listener です。
    */
  trait Listener {

    /**
      * 指定された [[MessageQueue]] に [[MessageQueue.take]] 可能なメッセージが準備できたときに true の引数で
      * 呼び出されます。pollable = true で呼び出された直後に poll(0) したとしても、他のスレッドの poll() が
      * すでにメッセージを獲得している場合は null を返す可能性があります。
      *
      * @param messageQueue メッセージの取り出し可能状態に変更があった MessageQueue
      * @param pollable     メッセージの取り出しが可能になったとき true、取り出せるメッセージがなくなったとき false
      */
    def messagePollable(@Nonnull messageQueue:MessageQueue, pollable:Boolean):Unit = None

    /**
      * 指定された [[MessageQueue]] で [[MessageQueue.offer()]] が可能になったときに true の引数で呼び出されます。
      *
      * @param messageQueue メッセージの受け取り可能状態に変更があった MessageQueue
      * @param offerable    メッセージの追加が可能になったとき true、キューのサイズを超えたとき false
      */
    def messageOfferable(@Nonnull messageQueue:MessageQueue, offerable:Boolean):Unit = None
  }

  /**
    * [[MessageQueue]] 上でメッセージの終端を表すために使用するインスタンス。実際の通信上には現れない。
    */
  private[MessageQueue] val END_OF_MESSAGE:Message = new Control(new Fields {})

}
