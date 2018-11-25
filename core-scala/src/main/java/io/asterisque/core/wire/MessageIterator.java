package io.asterisque.core.wire;

import io.asterisque.core.msg.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.NoSuchElementException;

/**
 * {@link MessageQueue#poll()} を使用して同期挙動でメッセージを列挙するためのクラス。
 */
class MessageIterator implements java.util.Iterator<Message>, MessageQueue.Listener {

  private final MessageQueue queue;
  private final Object signal = new Object();
  private Message next = null;

  MessageIterator(@Nonnull MessageQueue queue) {
    this.queue = queue;
  }

  /**
   * {@inheritDoc}
   *
   * @return 次に読み込み可能な要素が存在する場合 true
   */
  @Override
  public boolean hasNext() {
    next = nextMessage();
    return next != null;
  }

  /**
   * {@inheritDoc}
   *
   * @return 次の要素
   * @throws NoSuchElementException キューに要素が素材しない場合
   */
  @Override
  public Message next() throws NoSuchElementException {
    if (next == null) {
      throw new NoSuchElementException();
    }
    Message ret = next;
    next = null;
    return ret;
  }

  /**
   * {@inheritDoc}
   *
   * @param queue    キュー
   * @param pollable メッセージの取り出しが可能になったとき true、取り出せるメッセージがなくなったとき false
   */
  @Override
  public void messagePollable(@Nonnull MessageQueue queue, boolean pollable) {
    if (pollable) {
      wakeUp();
    }
  }

  /**
   * キューにメッセージが到着したり、キューがクローズされたときに呼び出されます。
   */
  void wakeUp() {
    synchronized (signal) {
      signal.notify();
    }
  }

  /**
   * メッセージキューから次のメッセージを参照します。キューがクローズされておりそれ以上のメッセージを取り出せない場合は null
   * を返します。
   *
   * @return キューから取り出したメッセージ
   */
  @Nullable
  private Message nextMessage() {
    synchronized (signal) {
      Message msg = queue.poll();
      while (msg == null && !queue.closed()) {
        try {
          signal.wait(1000);
        } catch (InterruptedException ex) {
          throw new IllegalStateException("message polling is interrupted", ex);
        }
        msg = queue.poll();
      }
      return msg;
    }
  }
}
