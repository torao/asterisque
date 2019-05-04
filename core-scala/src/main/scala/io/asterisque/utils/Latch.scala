package io.asterisque.utils

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}


/**
  * メッセージの同期ブロックを行うためのラッチ機構です。
  * 通常の状態では任意の処理を並列に実行するが、ロックがかかると新規の実行開始を停止する。
  * 効率化のため、既に実行中の処理が存在してもロックを行い新しい処理を停止することが出来る点で、厳密な
  * [[java.util.concurrent.locks.ReadWriteLock]] と異なる。
  *
  * @author Takami Torao
  */
final class Latch() {

  /**
    * ロックが解除されたときに待機中の全ての処理へ通知するためのシグナル。
    */
  private[this] val signal = new Object()

  /**
    * このラッチがロックされているかのフラグ。
    */
  private[this] val locked = new AtomicBoolean(false)
  private[this] val pendings = new AtomicInteger(0)

  def getPendings:Int = pendings.get

  /**
    * このラッチをオープンし従属する処理のブロッキングを解除します。
    * すでにオープンされている場合は何も行いません。
    */
  def open():Unit = {
    if(locked.compareAndSet(true, false)) {
      signal.synchronized {
        signal.notifyAll()
      }
    }
  }

  /**
    * このラッチをクローズし従属する処理をブロッキングします。
    *
    * @return この呼び出しによりロックがかけられたとき true、すでにロックがかかっていた場合 false
    */
  def lock():Boolean = this.locked.compareAndSet(false, true)

  /**
    * 指定された処理を実行します。
    *
    * @param exec 実行する処理
    * @throws InterruptedException ロック待機中に割り込まれた場合
    */
  @throws[InterruptedException]
  def exec(exec:Runnable):Unit = {
    signal synchronized {
      while(locked.get) {
        pendings.incrementAndGet()
        try {
          signal.wait()
        } finally {
          pendings.decrementAndGet()
        }
      }
    }

    exec.run()
  }
}
