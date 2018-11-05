package io.asterisque.msg;

import io.asterisque.Debug;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * 処理の中断状況を表すために {@link Close} に付加される情報です。
 *
 * @author Takami Torao
 */
public final class Abort extends Exception {

  /**
   * 予期しない状況によって処理が中断したことを示すコード。
   */
  public static final int Unexpected = -1;

  /**
   * セッションがクローズ処理に入ったため処理が中断されたことを示すコード。
   */
  public static final int SessionClosing = -2;

  /**
   * この中断理由を受信側で識別するためのコード値です。
   */
  public final int code;

  /**
   * この中断理由を人間が読める形式で表したメッセージです。
   */
  @Nonnull
  public final String message;

  /**
   * 中断コードとメッセージを指定して構築を行います。
   *
   * @param code    識別コード
   * @param message 人間が読める形式の中断理由
   */
  public Abort(int code, @Nonnull String message) {
    super(code + ": " + message);
    Objects.requireNonNull(message, "message shouldn't be null");
    this.code = code;
    this.message = message;
  }

  /**
   * このインスタンスを文字列化します。
   */
  @Override
  public String toString() {
    return "Abort(" + code + "," + Debug.toString(message) + ")";
  }

}
