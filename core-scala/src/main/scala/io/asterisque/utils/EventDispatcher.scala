package io.asterisque.utils

import java.util.concurrent.atomic.AtomicReference

import io.asterisque.utils.EventDispatcher._
import javax.annotation.Nonnull
import org.slf4j.LoggerFactory

import scala.annotation.tailrec

/**
  * イベントリスナの登録と参照を行う事ができるトレイトです。
  *
  * @tparam LISTENER 登録可能なイベントリスナの型
  */
trait EventDispatcher[LISTENER <: AnyRef] {

  private[this] val listeners = new AtomicReference[Seq[LISTENER]](Seq.empty)

  /**
    * 指定されたイベントリスナをこのインスタンスに登録します。
    *
    * @param listener イベントリスナ
    * @return このインスタンス
    */
  @Nonnull
  def addListener(listener:LISTENER):EventDispatcher[LISTENER] = _addListener(listener)

  @Nonnull
  @tailrec
  private[this] def _addListener(listener:LISTENER):EventDispatcher[LISTENER] = {
    val ls = listeners.get()
    if(listeners.compareAndSet(ls, ls :+ listener)) this else _addListener(listener)
  }

  /**
    * 指定されたイベントリスナをこのインスタンスから除外します。
    *
    * @param listener 除外するイベントリスナ
    * @return このインスタンス
    */
  @Nonnull
  def removeListener(listener:LISTENER):EventDispatcher[LISTENER] = _removeListener(listener)

  @Nonnull
  @tailrec
  private[this] def _removeListener(listener:LISTENER):EventDispatcher[LISTENER] = {
    val ls = listeners.get()
    if(listeners.compareAndSet(ls, ls.filterNot(l => l.eq(listener)))) this else _removeListener(listener)
  }

  /**
    * 登録されている全てのイベントリスナに対してイベントを通知するために列挙します。
    *
    * @param f コールバック
    */
  protected def foreach(f:LISTENER => Unit):Unit = listeners.get().foreach { listener =>
    try {
      f(listener)
    } catch {
      case ex:Throwable =>
        logger.error(s"fail to dispatch event call-back", ex)
    }
  }

}

object EventDispatcher {
  private[EventDispatcher] val logger = LoggerFactory.getLogger(classOf[EventDispatcher[_]])
}