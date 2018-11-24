package io.asterisque;


import io.asterisque.core.msg.Message;

import javax.annotation.Nonnull;

/**
 * 書き込み可能になった Wire が次の送信メッセージを参照したり、読み込んだメッセージを通知するためのインターフェースです。
 */
public interface Plug {

  /**
   * Wire が書き込み可能になったときに次の送信メッセージを参照するために呼び出されるメソッド。プラグインに送信可能な
   * メッセージが発生したときに {@link Wire#setWritable(boolean)} で制御されるため、プラグインに送信可能なメッセージが
   * 存在している状態でのみ呼び出されます。
   *
   * @return Wire に送信するメッセージ
   */
  @Nonnull
  Message produce();

  /**
   * Wire が受信したメッセージを渡すメソッド。{@link Wire#setReadable(boolean)} で呼び出しを抑止することができるが、false
   * に設定した時点で既にメッセージを受信していた場合は前後して呼び出される可能性がある。
   *
   * @param msg wire が受信したメッセージ
   */
  void consume(@Nonnull Message msg);

  /**
   * プラグに接続された Wire がクローズされたときにに呼び出されるメソッド。明示的に {@link Wire#close()} を呼び出した場合と、
   * 下層の通信実装が切断された場合に呼び出されます。
   *
   * @param wire クローズされた Wire
   */
  void onClose(@Nonnull Wire wire);

  /**
   * 問題分析のための識別文字列を参照します。
   */
  @Nonnull
  String id();
}
