package io.asterisque.msg;

import io.asterisque.Asterisque;
import io.asterisque.Debug;
import io.asterisque.Priority;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * 特定のファンクションに対してパイプのオープンを要求するメッセージです。
 *
 * @author Takami Torao
 */
public final class Open extends Message {

  /**
   * service id として使用できる UTF-8 文字列の最大バイト数 (文字数ではない) です。
   */
  public static final int MaxServiceId = 255;

  /**
   * このメッセージでオープンを要求するサービスを識別する ID です。
   */
  @Nonnull
  public final String serviceId;

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
  public Open(short pipeId, byte priority, @Nonnull String serviceId, short functionId, @Nonnull Object[] params) {
    super(pipeId);
    Objects.requireNonNull(serviceId);
    Objects.requireNonNull(params, "params shouldn't be null");
    if(serviceId.getBytes(Asterisque.UTF8).length > MaxServiceId){
      throw new IllegalArgumentException("service id too long: " + serviceId);
    }
    this.serviceId = serviceId;
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
  public Open(short pipeId, @Nonnull String serviceId, short functionId, @Nonnull Object[] params) {
    this(pipeId, Priority.Normal, serviceId, functionId, params);
  }

  /**
   * このインスタンスを文字列化します。
   */
  @Override
  public String toString() {
    return "Open(" + pipeId + "," + priority + "," + functionId + "," + Debug.toString(params) + ")";
  }

}
