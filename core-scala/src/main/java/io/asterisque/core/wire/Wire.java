package io.asterisque.core.wire;

import io.asterisque.Node;
import io.asterisque.Session;
import io.asterisque.core.msg.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLSession;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Optional;

/**
 * メッセージの伝達ラインを実装するインターフェースです。TCP 接続における非同期 Socket に相当し、Wire のクローズは TCP 接続の
 * クローズを意味します。{@link Session} に対して再接続が行われる場合、新しい Wire のインスタンスが生成されます。
 * <p>
 * メッセージのキュー/バッファリング、back pressure 等のフロー制御、再接続の処理は上位層で行われます。
 *
 * @author Takami Torao
 */
public interface Wire<NODE> extends AutoCloseable {

  /**
   * この Wire のリモート側 {@link Node} を参照します。
   */
  @Nonnull
  NODE node();

  /**
   * この Wire のローカル側アドレスを参照します。
   */
  @Nullable
  SocketAddress local();

  /**
   * この Wire のリモート側アドレスを参照します。
   */
  @Nullable
  SocketAddress remote();

  /**
   * こちら側の端点が接続を受け付けた場合に true を返します。プロトコルの便宜上どちらが master でどちらが worker かの役割を
   * 決定する必要がある場合に使用することができます。
   *
   * @return こちらの端点が接続を受け付けた側の場合 true
   */
  boolean isPrimary();

  /**
   * この Wire が受信したメッセージを渡したり、送信メッセージを取り出す Plug を設定します。
   * このメソッドにより有効な Plug が設定されていない限りは下層のネットワークからの読み出しは行われません。
   *
   * @param plug メッセージハンドラ
   */
  void bound(@Nonnull Plug<NODE> plug);

  void unbound(@Nonnull Plug<NODE> plug);

  /**
   * Plug から {@link Plug#produce()} で取り出し可能な送信メッセージが発生したときに true を設定し、送信するメッセージ
   * が一時的になくなったときに false を設定します。サブクラスはこのメソッドをオーバーライドして下層の通信実装から
   * {@link java.nio.channels.SelectionKey#OP_WRITE} のような出力可能の通知設定を制御することができます。
   * <p>
   * サブクラスはデフォルトで writable = false の状態を持つ必要があります。
   * スタブが設定されていない状態で writable を true に設定することはできません。
   *
   * @param writable スタブに送信可能なメッセージが発生した場合 true
   */
  // void setWritable(boolean writable);

  /**
   * Plug が新しいメッセージを受信できるようになり {@link Plug#consume(Message)} を呼び出し可能になったときに
   * true を設定し、これ以上メッセージを受信できなくなったときに false を設定します。true を指定することにより Wire は
   * ネットワークから新しいメッセージを読み込んで Plug に通知を行います。
   * <p>
   * サブクラスはデフォルトで readable = false の状態を持つ必要があります。
   *
   * @param readable Plug からメッセージを取り出し可能になったとき true
   */
  // void setReadable(boolean readable);

  /**
   * この Wire の通信相手の証明書セッションを参照します。ピアとの通信に認証が使用されていなければ Optional.empty() を返します。
   *
   * @return 通信の SSL セッション
   */
  @Nonnull
  Optional<SSLSession> session();

  /**
   * この Wire をクローズしリソースを解放します。Wire に設定されている Plug に対して onClose() が通知されます。
   */
  void close();

  /**
   * この Wire の接続先情報を人が読む目的の文字列として参照します。
   *
   * @return この Wire の接続情報
   */
  @Nonnull
  String toString();

}
