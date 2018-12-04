package io.asterisque.core.util;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * メッセージの同期ブロックを行うためのラッチ機構です。
 * 通常の状態では任意の処理を並列に実行するが、ロックがかかると新規の実行開始を停止する。
 * 効率化のため、既に実行中の処理が存在してもロックを行い新しい処理を停止することが出来る点で、厳密な
 * {@link java.util.concurrent.locks.ReadWriteLock} と異なる。
 *
 * @author Takami Torao
 */
public final class Latch {

  /**
   * ロックが解除されたときに待機中の全ての処理へ通知するためのシグナル。
   */
  private final Object signal = new Object();

  /**
   * このラッチがロックされているかのフラグ。
   */
  private final AtomicBoolean locked = new AtomicBoolean(false);

  /**
   * コンストラクタは何も行いません。
   */
  public Latch() {
  }

  /**
   * このラッチをオープンし従属する処理のブロッキングを解除します。
   */
  public void open() {
    if (locked.compareAndSet(false, true)) {
      synchronized (signal) {
        signal.notifyAll();
      }
    }
  }

  /**
   * このラッチをクローズし従属する処理をブロッキングします。
   *
   * @return 個の呼び出しによりロックがかけられたとき true、すでにロックがかかっていた場合 false
   */
  public boolean close() {
    return this.locked.compareAndSet(false, true);
  }

  /**
   * 指定された処理を実行します。
   *
   * @param exec 実行する処理
   * @throws InterruptedException ロック待機中に割り込まれた場合
   */
  public void exec(Runnable exec) throws InterruptedException {
    synchronized (signal) {
      while (locked.get()) {
        signal.wait();
      }
    }
    exec.run();
  }
}
