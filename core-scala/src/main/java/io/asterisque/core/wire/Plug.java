package io.asterisque.core.wire;


import io.asterisque.core.msg.Message;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Wire はサービスの処理とは無関係に非同期でネットワーク通信を行います。Plug は Wire と Session の間に位置して
 * メッセージの送受信キューの役割となるインターフェースです。
 * <p>
 * Plug はメッセージの取り出し可能状態と受け取り可能状態を Wire に通知します。これは TCP/IP レベルでの back pressure
 * を機能させることを意図しています。
 */
public interface Plug<NODE> {

  /**
   * 問題分析のための識別文字列を参照します。
   */
  @Nonnull
  String id();

  /**
   * Wire が書き込み可能になったときに次の送信メッセージを参照するために呼び出されるメソッド。プラグインに送信可能な
   * メッセージが発生したときに {@link Wire#setWritable(boolean)} で制御されるため、プラグインに送信可能なメッセージが
   * 存在している状態でのみ呼び出されます。
   *
   * @return Wire に送信するメッセージ
   */
  @Nullable
  Message produce();

  /**
   * Wire が受信したメッセージを渡すメソッド。{@link Wire#setReadable(boolean)} で呼び出しを抑止することができるが、false
   * に設定した時点で既にメッセージを受信していた場合は前後して呼び出される可能性がある。
   *
   * @param msg wire が受信したメッセージ
   */
  void consume(@Nonnull Message msg);

  /**
   * 指定された Listener をこの Plug に追加します。
   *
   * @param listener 追加するリスナ
   */
  void addListener(@Nonnull Listener listener);

  /**
   * 指定された Listener をこの Plug から削除します。
   *
   * @param listener 削除するリスナ
   */
  void removeListener(@Nonnull Listener listener);

  /**
   * プラグに接続された Wire がクローズされたときに呼び出されるメソッド。明示的に {@link Wire#close()} を呼び出した場合と、
   * 下層のネットワーク実装が切断された場合に呼び出されます。
   *
   * @param wire クローズされた Wire
   */
  void onClose(@Nonnull Wire<NODE> wire);

  /**
   * プラグに接続された Wire で例外が発生したときに呼び出されるメソッド。
   *
   * @param wire 例外の発生した wire
   * @param ex   発生した例外
   */
  void onException(@Nonnull Wire<NODE> wire, @Nonnull Throwable ex);

  /**
   * {@link Plug} のメッセージ送受信の準備状態に変更があったときに通知を受けるための Listener です。
   */
  interface Listener {

    /**
     * 指定された {@link Plug} からメッセージの取り出し {@link Plug#produce() produce()} 可能なメッセージが準備できた
     * ときに true の引数で呼び出されます。
     *
     * @param plug        メッセージの取り出し可能状態に変更があった Plug
     * @param produceable メッセージの取り出しが可能になったとき true、取り出せるメッセージがなくなったとき false
     */
    void messageProduceable(@Nonnull Plug plug, boolean produceable);

    /**
     * 指定された {@link Plug} がメッセージの受け取り {@link Plug#consume(Message) consume()} が可能になったときに
     * true の引数で呼び出されます。
     *
     * @param plug        メッセージの受け取り可能状態に変更があった Plug
     * @param consumeable メッセージの受け取りが可能になったとき true、メッセージを受け取れなくなったとき false
     */
    void messageConsumeable(@Nonnull Plug plug, boolean consumeable);
  }

}
