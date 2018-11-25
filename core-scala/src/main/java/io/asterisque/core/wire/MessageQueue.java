package io.asterisque.core.wire;


import io.asterisque.core.msg.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * {@link Wire} と Session の間に位置する非同期メッセージングの送受信キューです。この MessageQueue はメッセージの
 * 取り出し可能状態と受け取り可能状態を {@link Listener} を経由してそれらに通知します。これは TCP/IP レベルでの
 * back pressure を機能させることを意図しています。
 * <p>
 * MessageQueue はサイズを持ちますが、これは超過した場合に {@link Listener} 経由で通知を行うのみであり、ブロッキングや
 * 例外を伴うものではありません。
 */
public class MessageQueue implements AutoCloseable {
  private final BlockingQueue<Message> queue;
  private final int queueSize;
  private final List<Listener> listeners = new ArrayList<>();
  private final AtomicBoolean closed = new AtomicBoolean(false);

  MessageQueue(int queueSize) {
    if (queueSize <= 0) {
      throw new IllegalArgumentException("incorrect queue size: " + queueSize);
    }
    this.queue = new LinkedBlockingQueue<>();
    this.queueSize = queueSize;
  }

  /**
   * このキューからメッセージを取り出します。キューにメッセージが存在しない場合は {@code timeout} で指定された時間だけ
   * 待機し、それでも到達しない場合は null を返します。
   * <p>
   * メソッドの呼び出しでキューの空を検知した場合 {@link Listener} 経由で pollable = false が通知されます。また
   * メッセージ数がキューのサイズを下回った場合 offerable = true が通知されます。
   *
   * @param timeout タイムアウト
   * @param unit    タイムアウトで指定された数値の時間単位
   * @return キューから取り出したメッセージ、または null
   * @throws InterruptedException メッセージ待機中にスレッドが割り込まれた場合
   */
  @Nullable
  public Message poll(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
    synchronized (queue) {
      // close() が呼び出されていても正常に受理した分は取り出しは可能としている
      Message message = timeout <= 0 ? queue.poll() : queue.poll(timeout, unit);
      if (queue.isEmpty()) {
        for (Listener listener : listeners) {
          listener.messagePollable(this, false);
        }
      }
      if (queue.size() == queueSize - 1) {
        for (Listener listener : listeners) {
          listener.messageOfferable(this, true);
        }
      }
      return message;
    }
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
    synchronized (queue) {
      if (closed.get()) {
        throw new IllegalStateException("message queue has been closed.");
      }
      queue.offer(msg);
      if (queue.size() == 1) {
        for (Listener listener : listeners) {
          listener.messagePollable(this, true);
        }
      }
      if (queue.size() > queueSize) {
        for (Listener listener : listeners) {
          listener.messageOfferable(this, false);
        }
      }
    }
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
        Optional.ofNullable(iterator.get()).ifPresent(MessageIterator::wakeUp);
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
   * キューから取り出せるメッセージを同期処理で扱うためのストリーム {@link Stream} を参照します。このメソッドは
   * {@link #poll()} の同期版代替として利用することができます。
   *
   * @return メッセージのストリーム
   */
  public Stream<Message> stream() {
    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(
        iterator(), Spliterator.NONNULL | Spliterator.IMMUTABLE), false);
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
     * true の引数で呼び出されます。
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
