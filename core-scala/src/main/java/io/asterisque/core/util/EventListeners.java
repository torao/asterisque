package io.asterisque.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * イベントリスナを保存し呼び出しを行うクラスです。
 *
 * @param <LISTENER> イベントリスナ
 */
public class EventListeners<LISTENER> {
  private static final Logger logger = LoggerFactory.getLogger(EventListeners.class);

  /**
   * このインスタンスに登録されているイベントリスナ。
   */
  private List<LISTENER> listeners;

  /**
   * 指定されたイベントリスナを登録します。
   *
   * @param listener 登録するイベントリスナ
   */
  public synchronized void addListener(@Nonnull LISTENER listener) {
    if (this.listeners == null) {
      this.listeners = new ArrayList<>();
    }
    this.listeners.add(listener);
  }

  /**
   * 指定されたイベントリスナを削除します。
   *
   * @param listener 削除するイベントリスナ
   */
  public synchronized void removeListener(@Nonnull LISTENER listener) {
    if (this.listeners == null || !this.listeners.remove(listener)) {
      logger.warn("the event listener attempted to delete has not been registered: {}", listener);
    }
  }

  /**
   * すべてのイベントリスナに指定された Consumer を適用します。
   *
   * @param exec コールバック
   */
  public synchronized void foreach(@Nonnull Consumer<LISTENER> exec) {
    if (listeners != null) {
      for (LISTENER listener : listeners) {
        exec.accept(listener);
      }
    }
  }

}
