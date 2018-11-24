package io.asterisque.core.wire;

import io.asterisque.Wire;
import io.asterisque.core.msg.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

public abstract class PlugImpl implements Plug {

  private final String id;

  private final List<Listener> listeners = new ArrayList<>();

  private final int inQueueSize;

  private final int outQueueSize;

  private final Queue<Message> inbound;

  private final Queue<Message> outbound;

  protected PlugImpl(@Nonnull String id, int inQueueSize, int outQueueSize) {
    this.id = id;
    this.inQueueSize = inQueueSize;
    this.outQueueSize = outQueueSize;
    this.inbound = new LinkedList<>();
    this.outbound = new LinkedList<>();
  }

  /**
   * 問題分析のための識別文字列を参照します。
   */
  @Nonnull
  public String id() {
    return id;
  }

  /**
   * Wire が書き込み可能になったときに次の送信メッセージを参照するために呼び出されるメソッド。プラグインに送信可能な
   * メッセージが発生したときに {@link Wire#setWritable(boolean)} で制御されるため、プラグインに送信可能なメッセージが
   * 存在している状態でのみ呼び出されます。
   *
   * @return Wire に送信するメッセージ
   */
  @Nullable
  public Message produce() {
    synchronized (outbound) {
      Message message = outbound.isEmpty() ? null : outbound.poll();
      if (outbound.isEmpty()) {
        fireStateChange(listener -> listener.messageProduceable(this, false));
      }
      return message;
    }
  }

  /**
   * Wire が受信したメッセージを渡すメソッド。{@link Wire#setReadable(boolean)} で呼び出しを抑止することができるが、false
   * に設定した時点で既にメッセージを受信していた場合は前後して呼び出される可能性がある。
   *
   * @param msg wire が受信したメッセージ
   */
  public void consume(@Nonnull Message msg) {
    synchronized (inbound) {
      inbound.offer(msg);
      if (inbound.size() == 1) {
        fireStateChange(listener -> listener.messageConsumeable(this, true));
      } else if (inbound.size() > inQueueSize) {
        fireStateChange(listener -> listener.messageConsumeable(this, false));
      }
    }
  }

  /**
   * 指定された Listener をこの Plug に追加します。
   *
   * @param listener 追加するリスナ
   */
  public void addListener(@Nonnull Listener listener) {
    listeners.add(listener);
  }

  /**
   * 指定された Listener をこの Plug から削除します。
   *
   * @param listener 削除するリスナ
   */
  public void removeListener(@Nonnull Listener listener) {
    listeners.remove(listener);
  }

  protected void fireStateChange(@Nonnull Consumer<Listener> f) {
    listeners.forEach(f);
  }

  /**
   * プラグに接続された Wire がクローズされたときにに呼び出されるメソッド。明示的に {@link Wire#close()} を呼び出した場合と、
   * 下層のネットワーク実装が切断された場合に呼び出されます。
   *
   * @param wire クローズされた Wire
   */
  public abstract void onClose(@Nonnull Wire wire);

}
