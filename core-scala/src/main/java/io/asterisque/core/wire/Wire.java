package io.asterisque.core.wire;

import io.asterisque.Session;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * メッセージの伝達ラインを実装するインターフェースです。TCP 接続における非同期 Socket に相当し、Wire のクローズは TCP 接続の
 * クローズを意味します。{@link Session} に対して再接続が行われる場合、新しい Wire のインスタンスが生成されます。
 * <p>
 * このクラスではメッセージのキュー/バッファリングが行われます。back pressure 等のフロー制御、再接続の処理はより上位層で
 * 行われます。
 */
public abstract class Wire implements AutoCloseable {

  @Nonnull
  public final String name;

  /**
   * 受信メッセージのキュー。
   */
  @Nonnull
  public final MessageQueue inbound;

  /**
   * 送信メッセージのキュー。
   */
  @Nonnull
  public final MessageQueue outbound;

  /**
   * このワイヤーのリスナ。
   */
  private final List<Listener> listeners = new ArrayList<>();

  protected Wire(@Nonnull String name, int inboundQueueSize, int outboundQueueSize) {
    this.name = name;
    this.inbound = new MessageQueue(name + ":IN", inboundQueueSize);
    this.outbound = new MessageQueue(name + ":OUT", outboundQueueSize);
  }

  /**
   * この Wire のローカル側アドレスを参照します。ローカルアドレスが確定していない場合は null を返します。
   */
  @Nullable
  public abstract SocketAddress local();

  /**
   * この Wire のリモート側アドレスを参照します。リモートアドレスが確定していない場合は null を返します。
   */
  @Nullable
  public abstract SocketAddress remote();

  /**
   * こちら側の端点が接続を受け付けた場合に true を返します。プロトコルの便宜上どちらが master でどちらが worker かの役割を
   * 決定する必要がある場合に使用することができます。
   *
   * @return こちらの端点が接続を受け付けた側の場合 true
   */
  public abstract boolean isPrimary();

  /**
   * この Wire の通信相手の証明書セッションを参照します。ピアとの通信に認証が使用されていなければ Optional.empty() を返します。
   *
   * @return 通信の SSL セッション
   */
  @Nonnull
  public abstract Optional<SSLSession> session();

  /**
   * この Wire をクローズしリソースを解放します。登録されている {@link Listener} に対して wireClosed() が通知されます。
   */
  public void close() {
    inbound.close();
    outbound.close();
  }

  /**
   * 指定された Listener をこの Wire に追加します。
   *
   * @param listener 追加するリスナ
   */
  public void addListener(@Nonnull Listener listener) {
    listeners.add(listener);
  }

  /**
   * 指定された Listener をこの Wire から削除します。
   *
   * @param listener 削除するリスナ
   */
  public void removeListener(@Nonnull Listener listener) {
    listeners.remove(listener);
  }

  /**
   * サブクラスがこの Wire に登録されているリスナを列挙してイベントを通知するためのメソッドです。
   *
   * @param callback リスナのコールバック
   */
  protected void fireWireEvent(@Nonnull Consumer<Listener> callback) {
    listeners.forEach(callback);
  }

  public interface Listener {
    void wireClosed(@Nonnull Wire wire);

    void wireError(@Nonnull Wire wire, @Nonnull Throwable ex);
  }

}
