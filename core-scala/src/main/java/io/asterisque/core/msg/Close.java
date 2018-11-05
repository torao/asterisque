package io.asterisque.msg;

import io.asterisque.Debug;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * パイプのクローズを示すメッセージです。ファンクションの呼び出し結果として {@code result} もしくは {@code abort} を持つ事が
 * できます。{@code abort} が null の場合、{@code result} が有効です。
 *
 * @author Takami Torao
 */
public final class Close extends Message {

  /**
   * 処理が正常に終了した場合の結果です。Optional は Serializable ではないため null 可能な Object 型の参照を使用しています。
   */
  @Nullable
  public final Object result;

  /**
   * 中断によって処理が終了したことを示す値です。Optional は Serializable ではないため null 可能な Object 型の参照を使用
   * しています。
   */
  @Nullable
  public final Abort abort;

  /**
   * 処理成功の Close メッセージを構築します。
   *
   * @param pipeId 宛先のパイプ ID
   * @param result 成功として渡される結果
   */
  public Close(short pipeId, @Nullable Object result) {
    super(pipeId);
    this.result = result;
    this.abort = null;
  }

  /**
   * 処理中断の Close メッセージを構築します。
   *
   * @param pipeId 宛先のパイプ ID
   * @param abort  処理中断を表す例外
   */
  public Close(short pipeId, @Nonnull Abort abort) {
    super(pipeId);
    Objects.requireNonNull(abort, "abort shouldn't be null");
    this.result = null;
    this.abort = abort;
  }

  /**
   * このインスタンスを文字列化します。
   */
  @Override
  public String toString() {
    if (abort == null) {
      return "Close(" + pipeId + "," + Debug.toString(result) + ")";
    } else {
      return "Close(" + pipeId + "," + abort + ")";
    }
  }

  /**
   * 予期しない状態によってパイプを終了するときのクローズメッセージを作成します。
   *
   * @param pipeId 宛先のパイプ ID
   * @param msg    エラーメッセージ
   * @return 予期しない状況による処理中断を表すクローズメッセージ
   */
  public static Close unexpectedError(short pipeId, @Nonnull String msg) {
    return new Close(pipeId, new Abort(Abort.Unexpected, msg));
  }

}
