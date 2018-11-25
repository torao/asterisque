/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.core.util;

import java.util.concurrent.atomic.AtomicInteger;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// CircuitBreaker
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * 負荷値の増減によって回復可能な高負荷、回復不可能な高負荷の通知を行うクラスです。
 * 負荷値 {@link #load()} が {@link #softLimit} に達したら場合、回復可能な高負荷通知 {@link #overload(boolean)
 * overload(true)} を行い、さらに負荷値が {@link #hardLimit} に達したら回復不可能な高負荷通知
 * {@link #broken()} を行います。負荷値が {@link #softLimit} を下回った場合は {@link #overload(boolean)
 * overload(false)} 通知を行います。
 *
 * @author Takami Torao
 */
public abstract class CircuitBreaker {
  // 将来的には時間統計で現在の負荷状態を算出してメトリクスに使用したい。

  /**
   * このサーキットブレーカーの回復可能な通知閾値。カウントがこの数に達するとサブクラスの {@link #overload(boolean)
   * overload(true)}、下回ると {@link #overload(boolean) overload(false)} が呼び出されます。
   */
  public final int softLimit;

  /**
   * このサーキットブレーカーの回復不可能な通知閾値。カウントがこの数に達するとサブクラスの {@link #broken()}}
   * が呼び出されます。
   */
  public final int hardLimit;

  /**
   * このサーキットブレーカーの現在の負荷値。
   */
  private final AtomicInteger load = new AtomicInteger();

  /**
   * マルチスレッド環境での実行順序の影響で overload(true/false) の呼び出し順序が逆転する可能性があるためカウント
   * アップにより結果的に正しい ON/OFF 状態を設定する。
   */
  private final AtomicInteger overloadSwitch = new AtomicInteger(0);

  /**
   * このインスタンスに対して overload(true) が発動した回数。
   */
  private final AtomicInteger overload = new AtomicInteger(0);

  /**
   * このインスタンスに対して overload(true) が発動した回数。
   */
  private volatile boolean broken = false;

  // ==============================================================================================
  // コンストラクタ
  // ==============================================================================================
  /**
   * 指定された {@link #softLimit}、{@link #hardLimit} のサーキットブレーカーを構築します。
   *
   * @param softLimit ソフトリミット
   * @param hardLimit ハードリミット
   */
  protected CircuitBreaker(int softLimit, int hardLimit){
    if(softLimit <= 0){
      throw new IllegalArgumentException(String.format("soft limit should be positive: %d", softLimit));
    }
    if(hardLimit <= 0){
      throw new IllegalArgumentException(String.format("hard limit should be positive: %d", hardLimit));
    }
    if(softLimit >= hardLimit){
      throw new IllegalArgumentException(
        String.format("soft limit %s exceeds hard limit %d", softLimit, hardLimit));
    }
    this.softLimit = softLimit;
    this.hardLimit = hardLimit;
  }

  // ==============================================================================================
  // 負荷値の参照
  // ==============================================================================================
  /**
   * このサーキットブレーカーの現在の負荷値を参照します。
   */
  public int load(){
    return load.get();
  }

  // ==============================================================================================
  // 高負荷回数の参照
  // ==============================================================================================
  /**
   * このサーキットブレーカーで回復可能な高負荷が検知された回数を参照します。
   */
  public int overloadCount(){
    return overload.get();
  }

  // ==============================================================================================
  // 加算
  // ==============================================================================================
  /**
   * 負荷値を加算します。カウント値が {@link #softLimit} に達した場合 {@link #overload(boolean)} overload(true)}
   * が呼び出されます。同様に {@link #hardLimit} に達した場合は {@link #broken()} が呼び出されます。
   */
  public void increment(){
    int current = load.incrementAndGet();
    if(current >= softLimit) {
      if(current == softLimit) {
        if(overloadSwitch.incrementAndGet() == 1) {
          overload.incrementAndGet();
          overload(true);
        }
      } else if(current == hardLimit) {
        broken = true;
        broken();
      }
    }
  }

  // ==============================================================================================
  // 加算
  // ==============================================================================================
  /**
   * カウント値を加算します。カウント値が soft limit に達した場合 {@link #overload(boolean)} overload(true)}
   * が呼び出されます。同様に hard limit に達した場合は {@link #broken()} が呼び出されます。
   */
  public void decrement(){
    int current = load.getAndDecrement();
    if(current == softLimit){
      if(overloadSwitch.decrementAndGet() == 0){
        overload(false);
      }
    }
  }

  // ==============================================================================================
  // 回復可能な高負荷
  // ==============================================================================================
  /**
   * 負荷値が {@link #softLimit} に達したときに呼び出されます。
   * @param overload soft limit に達したとき true, soft limit を下回ったとき false
   */
  protected abstract void overload(boolean overload);

  // ==============================================================================================
  // 回復不可能な高負荷
  // ==============================================================================================
  /**
   * 負荷値が {@link #hardLimit} に達したときに呼び出されます。
   */
  protected abstract void broken();

  // ==============================================================================================
  // 回復不可能な高負荷
  // ==============================================================================================
  /**
   * このサーキットブレーカーの負荷値が {@link #hardLimit} に到達したかを返します。
   */
  public boolean isBroken(){
    return broken;
  }

}
