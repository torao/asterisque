package io.asterisque.core.codec;

import io.asterisque.core.msg.Message;

import javax.annotation.Nonnull;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Codec は {@link Message} を転送可能なバイナリに変換し、そのバイナリから元のメッセージを復元する機能を実装する
 * インターフェース。
 *
 * @author Takami Torao
 */
public interface MessageCodec {

  MessageCodec SimpleCodec = new SimpleCodec();
  MessageCodec MessagePackCodec = new MsgPackCodec();

  /**
   * シリアライズしバイト配列に変換された 1 メッセージ全体の最大バイナリ長を示す定数です。IPv4 でのデータ部最大長である
   * {@value} を表します。この値は UINT16 の最大値以下であることが保証されています。
   */
  int MaxMessageSize = 65507;

  /**
   * 指定されたメッセージをサブクラスが実装する形式で転送用のバイナリ形式にエンコードします。サブクラスは変換後のバイナリサイズが
   * {@link #MaxMessageSize} を超える場合に {@link CodecException} を発生させる必要があります。
   *
   * @param msg エンコードするメッセージ
   * @return エンコードされたバイトバッファ
   * @throws CodecException エンコードに失敗した場合
   */
  @Nonnull
  ByteBuffer encode(@Nonnull Message msg) throws CodecException;

  /**
   * 転送用のバイナリ表現をメッセージにデコードします。
   * <p>
   * このメソッドの呼び出しはデータを受信する都度行われます。従って、サブクラスはメッセージ全体を復元できるだけのデータを
   * 渡されなかった場合に {@code empty()} を返す必要があります。
   * <p>
   * パラメータの {@link java.nio.ByteBuffer} の位置は次回の呼び出しまで維持されます。このためサブクラスは復元した
   * メッセージの次の適切な読み出し位置を正しくポイントする必要があります。またメッセージを復元できるだけのデータを
   * 受信していない場合には読み出し位置を変更してはいけませんが、コーデック実装により無視できるバイナリが存在する場合は
   * バッファ位置を変更して {@code empty()} を返す事ができます。
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
