package io.asterisque.core.util;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

  private final AtomicInteger pendings = new AtomicInteger(0);

  /**
   * コンストラクタは何も行いません。
   */
  public Latch() {
  }

  public int getPendings(){
    return pendings.get();
  }

  /**
   * このラッチをオープンし従属する処理のブロッキングを解除します。
   * すでにオープンされている場合は何も行いません。
   */
  public void open() {
    if (locked.compareAndSet(true, false)) {
      synchronized (signal) {
        signal.notifyAll();
      }
    }
  }

  /**
   * このラッチをクローズし従属する処理をブロッキングします。
   *
   * @return この呼び出しによりロックがかけられたとき true、すでにロックがかかっていた場合 false
   */
  public boolean lock() {
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
        pendings.incrementAndGet();
        try {
          signal.wait();
        } finally {
          pendings.decrementAndGet();
        }
      }
    }
    exec.run();
  }
}
