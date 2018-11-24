package io.asterisque;

import io.asterisque.core.msg.Open;

/**
 * {@link Pipe} または {@link Open} ごとに設定するメッセージの優先度定数とそのユーティリティ機能を定義
 * しています。
 *
 * @author Takami Torao
 * @see Open#priority
 */
public final class Priority {

  /**
   * 最も高い優先度を示す定数。
   */
  public static final byte Max = Byte.MAX_VALUE;

  /**
   * 最も低い優先度を示す定数。
   */
  public static final byte Min = Byte.MIN_VALUE;

  /**
   * 通常の優先度を示す定数。
   */
  public static final byte Normal = 0;

  /**
   * コンストラクタはクラス内に隠蔽されています。
   */
  private Priority() {
  }

  /**
   * 指定された優先度を一つ高い値に変換します。返値は {{priority}} 以上の値となります。
   *
   * @param priority 一つ高い値を参照する優先度
   * @return 指定された優先度以上の値
   */
  public static byte upper(byte priority) {
    return (byte) Math.min(priority + 1, Max);
  }

  /**
   * 指定された優先度を一つ低い値に変換します。返値は {{priority}} 以下の値となります。
   *
   * @param priority 一つ低い値を参照する優先度
   * @return 指定された優先度以下の値
   */
  public static byte lower(byte priority) {
    return (byte) Math.max(priority - 1, Min);
  }
}
