package io.asterisque.codec;

import io.asterisque.msg.Message;

import javax.annotation.Nonnull;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * {@link Message} のシリアライズとデシリアライズを行うための機能を実装するインターフェースです。
 *
 * @author Takami Torao
 */
public interface Codec {

  /**
   * シリアライズしバイト配列に変換された 1 メッセージの最大バイナリ長です。IPv4 でのデータ部最大長である {@value} を表します。
   */
  int MaxMessageSize = 65507;

  /**
   * 指定されたメッセージをサブクラスが実装する形式でバイナリ形式にシリアライズします。変換後のバイナリサイズが
   * {@link #MaxMessageSize} を超える場合には {@link CodecException} が発生します。
   *
   * @param msg エンコードするメッセージ
   * @return エンコードされたバイトバッファ
   * @throws CodecException シリアライズに失敗した場合
   */
  @Nonnull
  ByteBuffer encode(@Nonnull Message msg) throws CodecException;

  /**
   * 指定されたバイナリ表現のメッセージをデコードします。
   * <p>
   * このメソッドの呼び出しはデータを受信する都度行われます。従って、サブクラスはメッセージ全体を復元できるだけデータを受信して
   * いない場合に {@code empty()} を返す必要があります。
   * <p>
   * パラメータの {@link java.nio.ByteBuffer} の位置は次回の呼び出しまで維持されます。このためサブクラスは復元したメッセージの
   * 次の適切な読み出し位置を正しくポイントする必要があります。またメッセージを復元できるだけのデータを受信していない場合には
   * 読み出し位置を変更すべきではありません。コーデック実装により無視できるバイナリが存在する場合はバッファ位置を変更して
   * {@code empty()} を返す事が出来ます。
   * <p>
   * メッセージのデコードに失敗した場合は {@link CodecException} が発生します。
   *
   * @param buffer デコードするメッセージ
   * @return デコードしたメッセージ
   * @throws CodecException デコードに失敗した場合
   */
  @Nonnull
  Optional<Message> decode(@Nonnull ByteBuffer buffer);

}
