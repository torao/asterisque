package io.asterisque.msg;

import io.asterisque.Asterisque;
import io.asterisque.Debug;
import io.asterisque.codec.Codec;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * Control はフレームワークによって他のメッセージより優先して送信される制御メッセージを表します。
 *
 * @author Takami Torao
 */
public final class Control extends Message {

  /**
   * 通信を開始したときにピア間の設定を同期するための制御コードです。バイナリストリームの先頭で {@code *Q} として出現するように
   * {@link Codec.Msg#Control} が {@code *}、{@code SyncConfig} が {@code Q} の値を取ります。
   * SyncConfig を持つ制御メッセージのバイナリフィールドはヘルパークラス {@link io.asterisque.msg.SyncConfig} 経由で
   * 参照することができます。
   */
  public static final byte SyncConfig = 'Q';

  /**
   * セッションの終了を表す制御コードです。この制御メッセージに有効なデータは付随しません。
   */
  public static final byte Close = 'C';

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
    this(code, Asterisque.EmptyBytes);
  }

  /**
   * このインスタンスを文字列化します。
   */
  @Override
  public String toString() {
    return "Control(" + ((char) code) + "," + Debug.toString(data) + ")";
  }

}
