package io.asterisque.wire.gateway

import java.util.NoSuchElementException
import java.util.concurrent.TimeUnit

import io.asterisque.wire.message.Message


/**
  * [[MessageQueue.iterator()]] を使用して同期挙動でメッセージを列挙するためのクラス。
  */
private[gateway] class MessageIterator(val queue:MessageQueue) extends Iterator[Message] {
  private[this] var _next:Message = _

  /**
    * @return 次に読み込み可能な要素が存在する場合 true
    */
  override def hasNext:Boolean = {
    try {
      _next = queue.poll(Long.MaxValue, TimeUnit.SECONDS)
    } catch {
      case ex:InterruptedException =>
        throw new IllegalStateException("message polling is interrupted", ex)
    }
    _next != null
  }

  /**
    * @return 次の要素
    * @throws NoSuchElementException キューに要素が素材しない場合
    */
  @throws[NoSuchElementException]
  override def next():Message = {
    if(_next == null) {
      throw new NoSuchElementException()
    }
    val ret = _next
    _next = null
    ret
  }
}
