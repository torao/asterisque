/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.core.util;

import java.util.concurrent.atomic.AtomicInteger;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Latch
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * {@link CircuitBreaker} に連動してメッセージの流量調整を行うためのラッチ機構。
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
   * 取得しているロックの数。0 の場合にロックが解除されている。重複呼び出しまたは順序逆転に対処するため boolean
   * ではなく int で保持している。
   */
  private final AtomicInteger lock = new AtomicInteger(0);

  /**
   * コンストラクタは何も行いません。
   */
  public Latch(){ }

  /**
   * このラッチをロックまたはロック解除します。
   * @param lock ラッチをロックする場合 true
   */
  public void lock(boolean lock){
    if(lock){
      this.lock.incrementAndGet();
    } else if(this.lock.decrementAndGet() == 0){
      synchronized(signal){
        signal.notifyAll();
      }
    }
  }

  /**
   * 指定された処理を実行します。
   * @param exec 実行する処理
   * @throws InterruptedException ロック待機中に割り込まれた場合
   */
  public void exec(Runnable exec) throws InterruptedException {
    if(lock.get() != 0){
      synchronized(signal){
        while(lock.get() != 0){
          signal.wait();
        }
      }
    }
    exec.run();
  }
}
