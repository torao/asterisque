package io.asterisque.core.msg;

import io.asterisque.Asterisque;
import io.asterisque.core.Debug;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Objects;

/**
 * Control はフレームワークによって他のメッセージより優先して送信される制御メッセージを表します。
 * {@link Message#pipeId} は無視されます。
 *
 * @author Takami Torao
 */
public final class Control extends Message {

  /**
   * 通信を開始したときにピア間の設定を同期するための制御コードです。バイナリストリームの先頭で {@code *Q} として出現するように
   * {@link io.asterisque.core.codec.MessageFieldCodec.Msg#Control} が {@code *}、{@code SyncSession} が {@code Q}
   * の値を取ります。
   * SyncSession を持つ制御メッセージのバイナリフィールドはヘルパークラス
   * {@link SyncSession} 経由で参照することができます。
   */
  public static final byte SyncSession = 'Q';

  /**
   * セッションの終了を表す制御コードです。この制御メッセージに有効なデータは付随しません。
   */
  public static final byte Close = 'C';

  /**
   * メッセージストリームの終了を表す制御コードです。予約のみで実際の通信上では使用していません。
   */
  private static final byte EndOfMessage = ']';

  /**
   * メッセージストリーム上でメッセージの終端を表すために使用することのできるインスタンス。
   */
  public static final Message EOM = new Control(Control.EndOfMessage);

  /**
   * このインスタンスが表す制御コードです。
   */
  public final byte code;

  /**
   * コード値に付属するデータを表すバイナリです。
   */
  @Nonnull
  public final byte[] data;

  /**
   * Control メッセージを構築します。
   *
   * @param code 制御コード
   * @param data コード値に依存するバイナリデータ
   */
  public Control(byte code, @Nonnull byte[] data) {
    super((short) 0 /* not used */);
    Objects.requireNonNull(data, "data is null");
    this.code = code;
    this.data = data;
  }

  /**
   * バイナリデータを持たない Control メッセージを構築します。
   *
   * @param code 制御コード
   */
  public Control(byte code) {
    this(code, Asterisque.Empty.Bytes);
  }

  /**
   * このインスタンスを文字列化します。
   */
  @Override
  @Nonnull
  public String toString() {
    return String.format("Control(%s,%s)", (char) code, Debug.toString(data));
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(data) ^ (code & 0xFF);
  }

  @Override
  public boolean equals(Object obj) {
    if (super.equals(obj) && obj instanceof Control) {
      Control other = (Control) obj;
      return this.code == other.code && Arrays.equals(this.data, other.data);
    }
    return false;
  }

}
