package io.asterisque.core.wire;

import io.asterisque.core.msg.Message;

import javax.annotation.Nonnull;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

/**
 * {@link MessageQueue#poll()} を使用して同期挙動でメッセージを列挙するためのクラス。
 */
class MessageIterator implements java.util.Iterator<Message> {

  private final MessageQueue queue;
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
    try {
      next = queue.poll(Long.MAX_VALUE, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      throw new IllegalStateException("message polling is interrupted", ex);
    }
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
}
