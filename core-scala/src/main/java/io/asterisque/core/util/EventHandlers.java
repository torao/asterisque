package io.asterisque.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * イベント発生時に実行する処理を保持するクラス。
 */
public final class EventHandlers<T> implements Consumer<T> {
  private static final Logger logger = LoggerFactory.getLogger(EventHandlers.class);

  /**
   * このインスタンスが呼び出し対象としているイベントハンドラ。
   */
  private final List<Consumer<T>> listeners = new ArrayList<>();

  public EventHandlers() {
  }

  /**
   * 指定されたイベントハンドラを追加します。
   *
   * @param f 追加するイベントハンドラ
   */
  @Nonnull
  public EventHandlers<T> add(@Nonnull Consumer<T> f) {
    synchronized (listeners) {
      listeners.add(f);
    }
    return this;
  }

  /**
   * 指定されたイベントハンドラセットのハンドラをすべてこのインスタンスに設定します。
   *
   * @param h ハンドラを取り込むハンドラ
   */
  @Nonnull
  public EventHandlers<T> addAll(@Nonnull EventHandlers<T> h) {
    synchronized (listeners) {
      listeners.addAll(h.listeners);
    }
    return this;
  }

  /**
   * 指定されたイベントハンドラを削除します。
   *
   * @param f 削除するイベントハンドラ
   */
  @Nonnull
  public EventHandlers<T> remove(@Nonnull Consumer<T> f) {
    synchronized (listeners) {
      listeners.remove(f);
    }
    return this;
  }

  /**
   * このインスタンスに登録されているすべてのイベントハンドラに引数 `s` で通知を行います。
   *
   * @param s イベントハンドラの呼び出しパラメータ
   */
  @Override
  public void accept(T s) {
    listeners.forEach(l -> {
      try {
        l.accept(s);
        if (logger.isTraceEnabled()) {
          logger.trace(String.format("callback %s(%s)", l.getClass().getSimpleName(), s));
        }
      } catch (Throwable ex) {
        logger.error(String.format("unexpected wsCaughtException on calling %s(%s)", l.getClass().getSimpleName(), s));
      }
    });
  }

}
