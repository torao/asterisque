/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// EventHandlers
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * イベント発生時に実行する処理を保持するクラス。
 * @author Takami Torao
 */
public final class EventHandlers<T> implements Consumer<T> {
  private static final Logger logger = LoggerFactory.getLogger(EventHandlers.class);

  /**
   * このインスタンスが呼び出し対象としているイベントハンドラ。
   */
  private final List<Consumer<T>> listeners = new ArrayList<>();

  // ==============================================================================================
  // イベントハンドラの追加
  // ==============================================================================================
  /**
   * 指定されたイベントハンドラを追加します。
   * @param f 追加するイベントハンドラ
   */
  public EventHandlers<T> add(Consumer<T> f) {
    synchronized(listeners){
      listeners.add(f);
    }
    return this;
  }

  // ==============================================================================================
  // イベントハンドラの追加
  // ==============================================================================================
  /**
   * 指定されたイベントハンドラセットのハンドラをすべてこのインスタンスに設定します。
   * @param h ハンドラを取り込むハンドラ
   */
  public EventHandlers<T> addAll(EventHandlers<T> h) {
    synchronized(listeners){
      listeners.addAll(h.listeners);
    }
    return this;
  }

  // ==============================================================================================
  // イベントハンドラの削除
  // ==============================================================================================
  /**
   * 指定されたイベントハンドラを削除します。
   * @param f 削除するイベントハンドラ
   */
  public EventHandlers<T> remove(Consumer<T> f){
    synchronized(listeners){
      listeners.remove(f);
    }
    return this;
  }

  // ==============================================================================================
  // イベントハンドラへの通知
  // ==============================================================================================
  /**
   * このインスタンスに登録されているすべてのイベントハンドラに引数 `s` で通知を行います。
   * @param s イベントハンドラの呼び出しパラメータ
   */
  @Override
  public void accept(T s){
    listeners.forEach(l -> {
      try {
        l.accept(s);
        if(logger.isTraceEnabled()){
          logger.trace(String.format("callback %s(%s)", l.getClass().getSimpleName(), s));
        }
      } catch(Throwable ex){
        logger.error(String.format("unexpected wsCaughtException on calling %s(%s)", l.getClass().getSimpleName(), s));
      }
    });
  }

}
