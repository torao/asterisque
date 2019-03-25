/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import org.slf4j.LoggerFactory

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// EventHandlers
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * イベント発生時に実行する処理を保持するクラス。
 * @author Takami Torao
 */
private[asterisk] final class EventHandlers[T] {
  import EventHandlers._

  /**
   * このインスタンスが呼び出し対象としているイベントハンドラ。
   */
  private val listeners = new AtomicReference(Seq[(T)=>Unit]())

  // ==============================================================================================
  // イベントハンドラの追加
  // ==============================================================================================
  /**
   * 指定されたイベントハンドラを追加します。
   * @param f 追加するイベントハンドラ
   */
  @tailrec
  def ++(f:(T)=>Unit):EventHandlers[T] = {
    val l = listeners.get()
    if(! listeners.compareAndSet(l, l.+:(f))){
      ++(f)
    } else {
      this
    }
  }

  // ==============================================================================================
  // イベントハンドラの追加
  // ==============================================================================================
  /**
   * 指定されたイベントハンドラセットのハンドラをすべてこのインスタンスに設定します。
   * @param h ハンドラを取り込むハンドラ
   */
  def +++(h:EventHandlers[T]):EventHandlers[T] = {
    h.listeners.get().foreach{ l => ++(l) }
    this
  }

  // ==============================================================================================
  // イベントハンドラの削除
  // ==============================================================================================
  /**
   * 指定されたイベントハンドラを削除します。
   * @param f 削除するイベントハンドラ
   */
  @tailrec
  def --(f:(T)=>Unit):EventHandlers[T] = {
    val l = listeners.get()
    if(! listeners.compareAndSet(l, l.filter{ _ != f })){
      --(f)
    } else {
      this
    }
  }

  // ==============================================================================================
  // イベントハンドラへの通知
  // ==============================================================================================
  /**
   * このインスタンスに登録されているすべてのイベントハンドラに引数 `s` で通知を行います。
   * @param s イベントハンドラの呼び出しパラメータ
   */
  def apply(s:T):Unit = listeners.get().foreach{ l =>
    try {
      l(s)
      if(logger.isTraceEnabled){
        logger.trace(s"callback ${l.getClass.getSimpleName}($s)")
      }
    } catch {
      case ex:Throwable if ! ex.isInstanceOf[ThreadDeath] =>
        logger.error(s"unexpected wsCaughtException on calling ${l.getClass.getSimpleName}($s)", ex)
    }
  }
}

object EventHandlers {
  private[EventHandlers] val logger = LoggerFactory.getLogger(classOf[EventHandlers[_]])
}