package io.asterisque.core.wire;


import io.asterisque.core.msg.Control;
import io.asterisque.core.msg.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link Wire} と Session の間に位置する非同期メッセージングの送受信キューです。この MessageQueue はメッセージの
 * 取り出し可能状態と受け取り可能状態を {@link Listener} を経由してそれらに通知します。これは TCP/IP レベルでの
 * back pressure を機能させることを意図しています。
 * <p>
 * MessageQueue はサイズを持ちますが、これは超過した場合に {@link Listener} 経由で通知を行うのみであり、ブロッキングや
 * 例外を伴うものではありません。
 */
public class MessageQueue implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(MessageQueue.class);

  @Nonnull
  public final String name;

  /**
   * メッセージキュー。{@link #poll()} 時に queue のサイズ確認のための同期を行わないように、このメッセージを poll()
   * した後のサイズを併せて保存する (WAIT の発生する poll() を synchronized すると {@link #offer(Message)} と競合して
   * デッドロックする)。
   */
  @Nonnull
  private final BlockingQueue<Message> queue;

  private final int cooperativeLimit;
  private final List<Listener> listeners = new ArrayList<>();
  private final AtomicBoolean closed = new AtomicBoolean(false);

  MessageQueue(@Nonnull String name, int cooperativeLimit) {
    if (cooperativeLimit <= 0) {
      throw new IllegalArgumentException("incorrect queue size: " + cooperativeLimit);
    }
    this.name = name;
    this.queue = new LinkedBlockingQueue<>();
    this.cooperativeLimit = cooperativeLimit;
  }

  /**
   * このキューで保留しているメッセージ数を参照します。
   *
   * @return キューのメッセージ数
   */
  public int size() {
    return queue.size();
  }

  /**
   * このキューに保存できる協調的上限サイズを参照します。アプリケーションはこの数値を超えてメッセージを {@link #offer(Message)}
   * することができますが、offerable = false のコールバックが発生します。
   *
   * @return キューの協調的上限サイズ
   */
  public int cooperativeLimit() {
    return cooperativeLimit;
  }

  /**
   * このキューの状態を文字列化します。
   *
   * @return キューの文字列表現
   */
  @Override
  public String toString() {
    return String.format("{%s,size=%d/%d%s}", name, size(), cooperativeLimit, closed() ? ",CLOSED" : "");
  }

  /**
   * このキューからメッセージを取り出します。指定されたタイムアウトまでにキューにメッセージが到達しなかった場合は null を
   * 返します。{@code timeout} に 0 以下の値をしてした場合は即座に処理を返します。
   * <p>
   * メソッドの呼び出しでキューの空を検知した場合 {@link Listener} 経由で pollable = false が通知されます。また
   * メッセージ数がキューのサイズを下回った場合 offerable = true が通知されます。
   * <p>
   * すでにクローズされているキューに対する poll() や、メッセージ待機中に外部スレッドからクローズされた場合、この
   * メソッドは即時に null を返します。
   *
   * @param timeout タイムアウト (0 以下の場合は即座に返す)
   * @param unit    タイムアウトで指定された数値の時間単位
   * @return キューから取り出したメッセージ、または null
   * @throws InterruptedException メッセージ待機中にスレッドが割り込まれた場合
   */
  @Nullable
  public Message poll(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
    // close() が呼び出されていても正常に受理した分は取り出しは可能としている
    Message message;
    int queueSize;
    synchronized (queue) {
      message = queue.poll();
      if (message == null && timeout > 0) {
        queue.wait(unit.toMillis(timeout));
        message = queue.poll();
      }
      queueSize = queue.size();
    }
    logger.trace("{}>> {} @ {}", name, message, Thread.currentThread().getName());

    // キューからメッセージが取り出しできなくなった
    if (queueSize == 0) {
      logger.trace("messagePollable({}, {})", this, false);
      for (Listener listener : listeners) {
        listener.messagePollable(this, false);
      }
    }

    // キューにメッセージが追加できるようになった
    if (queueSize == this.cooperativeLimit - 1 && !closed()) {
      logger.trace("messageOfferable({}, {})", this, true);
      for (Listener listener : listeners) {
        listener.messageOfferable(this, true);
      }
    }

    if (message != null && message.equals(Control.EOM)) {
      synchronized (queue) {
        offerAndNotify(Control.EOM);  // ほかに待機しているスレッドのために EOM を再投入する
      }
      return null;
    }
    return message;
  }

  /**
   * このキューからメッセージを取り出します。キューにメッセージが存在しない場合は即座に null を返します。
   * <p>
   * メソッドの呼び出しでキューの空を検知した場合 {@link Listener} 経由で pollable = false が通知されます。また
   * メッセージ数がキューのサイズを下回った場合 offerable = true が通知されます。
   *
   * @return キューから取り出したメッセージ、または null
   */
  @Nullable
  public Message poll() {
    try {
      return poll(0, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      throw new IllegalStateException("BlockingQueue.poll() without timeout is interrupted", ex);
    }
  }

  /**
   * このキューにメッセージを追加します。この呼び出しによりメッセージ数がキューのサイズを超えた場合 {@link Listener}
   * 経由で offerable = false を通知しますが、メッセージの追加そのものは正常に完了します。また空の状態で呼び出された
   * 場合は pollable = true が通知されます。
   *
   * @param msg キューに追加するメッセージ
   * @throws IllegalStateException キューがクローズされている場合
   */
  public void offer(@Nonnull Message msg) throws IllegalStateException {
    if (msg.equals(Control.EOM)) {
      logger.debug("closing queue by end-of-message offer");
      close();
      return;
    }
    int queueSize;
    synchronized (queue) {
      if (closed.get()) {
        throw new IllegalStateException("message queue has been closed.");
      }
      queueSize = offerAndNotify(msg);
    }
    logger.trace("{}<< {} @ {}", name, msg, Thread.currentThread().getName());

    // コールバック先で別のキュー操作を行う事ができるようにクリティカルセクションの外でリスナを呼び出す
    if (queueSize == 1) {
      logger.trace("messagePollable({}, {})", this, true);
      for (Listener listener : listeners) {
        listener.messagePollable(this, true);
      }
    }
    if (queueSize >= this.cooperativeLimit) {
      logger.trace("messageOfferable({}, {})", this, false);
      for (Listener listener : listeners) {
        listener.messageOfferable(this, false);
      }
    }
  }

  /**
   * 指定されたメッセージをキューに追加しメッセージを待機しているスレッドに通知します。このメソッドの呼び出しは {@link #queue}
   * に対するモニターを獲得している必要があります。
   *
   * @param msg キューに追加するメッセージ
   * @return 追加後のキューのサイズ
   */
  private int offerAndNotify(@Nonnull Message msg) {
    assert Thread.holdsLock(queue);
    queue.offer(msg);
    queue.notify();     // キューを待機しているスレッドに通知
    return queue.size();
  }

  /**
   * このキューがクローズされているかを参照します。
   *
   * @return クローズされている場合 true
   */
  public boolean closed() {
    return closed.get();
  }

  /**
   * このキューをクローズします。この操作によりキューは新しいメッセージの {@link #offer(Message)} を受け付けなくなります。
   * ただしキューに保存されているメッセージの取り出しは可能です。
   */
  public void close() {
    synchronized (queue) {
      if (closed.compareAndSet(false, true)) {
        int queueSize = offerAndNotify(Control.EOM);
        logger.trace("close(), {} messages remain", queueSize - 1);
      }
    }
  }

  private final AtomicReference<MessageIterator> iterator = new AtomicReference<>();

  /**
   * キューから取り出せるメッセージを同期処理で扱うための列挙 {@link Iterator} を参照します。このメソッドは
   * {@link #poll()} の同期版代替として利用することができます。
   *
   * @return メッセージの iterator
   */
  public Iterator<Message> iterator() {
    iterator.compareAndSet(null, new MessageIterator(this));
    return iterator.get();
  }

  /**
   * 指定された Listener をこの MessageQueue に追加します。
   *
   * @param listener 追加するリスナ
   */
  public void addListener(@Nonnull Listener listener) {
    listeners.add(listener);
  }

  /**
   * 指定された Listener をこの MessageQueue から削除します。
   *
   * @param listener 削除するリスナ
   */
  public void removeListener(@Nonnull Listener listener) {
    listeners.remove(listener);
  }

  /**
   * {@link MessageQueue} のメッセージ送受信の準備状態に変更があったときに通知を受けるための Listener です。
   */
  public interface Listener {

    /**
     * 指定された {@link MessageQueue} に {@link MessageQueue#poll() poll()} 可能なメッセージが準備できたときに
     * true の引数で呼び出されます。pollable = true で呼び出された直後に poll(0) したとしても、他のスレッドの poll() が
     * すでにメッセージを獲得している場合は null を返す可能性があります。
     *
     * @param messageQueue メッセージの取り出し可能状態に変更があった MessageQueue
     * @param pollable     メッセージの取り出しが可能になったとき true、取り出せるメッセージがなくなったとき false
     */
    default void messagePollable(@Nonnull MessageQueue messageQueue, boolean pollable) {
    }

    /**
     * 指定された {@link MessageQueue} で {@link MessageQueue#offer(Message) offer()} が可能になったときに
     * true の引数で呼び出されます。
     *
     * @param messageQueue メッセージの受け取り可能状態に変更があった MessageQueue
     * @param offerable    メッセージの追加が可能になったとき true、キューのサイズを超えたとき false
     */
    default void messageOfferable(@Nonnull MessageQueue messageQueue, boolean offerable) {
    }
  }

}
