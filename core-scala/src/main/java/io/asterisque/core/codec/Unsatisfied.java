package io.asterisque.core.codec;

/**
 * アプリケーションが使用する値をメッセージに変換できない場合や、メッセージから値を復元できない場合に使用される例外です。
 * この例外の発生はエラー状況ではなく、さらなるデータが必要な場合や引き続き変換を行う必要がある場合に使用されます。
 * <p>
 * 読み込み済みのバイナリがメッセージを復元するために不十分である場合に {@link Unmarshal} の読み出し処理で発生します。
 * この例外が発生した場合、データを復元するためにさらなるバイナリを読み出してデータ復元を再実行する必要があることを
 * {@code MessageCodec} に通知します。
 * <p>
 * {@link VariableCodec} においては複数存在する変数の変換/復元処理で引き続き他の変換後方を探す必要がある場合に使用
 * されます。変換結果として null 値を取り得ることに対して {@link java.util.Optional} が {@code null} を許容しない
 * ため例外で実装されます。
 */
public class Unsatisfied extends Exception {
  public Unsatisfied() {
  }

  public Unsatisfied(String msg) {
    super(msg);
  }
}
