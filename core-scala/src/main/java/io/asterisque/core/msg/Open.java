package io.asterisque.core.msg;

import io.asterisque.Priority;
import io.asterisque.core.Debug;
import io.asterisque.core.codec.VariableCodec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Objects;

/**
 * 特定のファンクションに対してパイプのオープンを要求するメッセージです。
 * オープン要求の対象となるサービスはセッション開始時に決定しています。
 *
 * @author Takami Torao
 */
public final class Open extends Message {

  /**
   * このメッセージでオープンを要求するファンクションを識別する ID です。
   */
  public final short functionId;

  /**
   * ファンクションの呼び出し時に渡す引数です。
   */
  @Nonnull
  public final Object[] params;

  /**
   * この Open によって開かれるパイプの同一セッション内での優先度を表す整数です。
   */
  public final byte priority;

  /**
   * オープンするパイプの優先度を指定して Open メッセージを構築します。
   *
   * @param pipeId     新しく要求するパイプの ID
   * @param priority   ファンクション呼び出しのプライオリティ
   * @param functionId オープンを要求するファンクション ID
   * @param params     ファンクションの呼び出しパラメータ
   */
  public Open(short pipeId, byte priority, short functionId, @Nonnull Object[] params) {
    super(pipeId);
    Objects.requireNonNull(params, "params shouldn't be null");
    assert VariableCodec.isTransferable(Arrays.asList(params)) : Debug.toString(params);
    this.priority = priority;
    this.functionId = functionId;
    this.params = params;
  }

  /**
   * デフォルトの優先度を持つ Open メッセージを構築します。
   *
   * @param pipeId     新しく要求するパイプの ID
   * @param functionId オープンを要求するファンクション ID
   * @param params     ファンクションの呼び出しパラメータ
   */
  public Open(short pipeId, short functionId, @Nonnull Object[] params) {
    this(pipeId, Priority.Normal, functionId, params);
  }

  /**
   * このインスタンスを文字列化します。
   */
  @Nonnull
  @Override
  public String toString() {
    return String.format("Open(%s,%s,%s,%s)", pipeId, priority, functionId, Debug.toString(params));
  }

  @Override
  public int hashCode() {
    return (((pipeId & 0xFFFF) << 16) | (functionId & 0xFFFF)) ^ (priority & 0xFF) + Arrays.hashCode(params);
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (super.equals(obj) && obj instanceof Open) {
      Open other = (Open) obj;
      return this.pipeId == other.pipeId && this.priority == other.priority && this.functionId == other.functionId &&
          Arrays.equals(this.params, other.params);
    }
    return false;
  }
}
