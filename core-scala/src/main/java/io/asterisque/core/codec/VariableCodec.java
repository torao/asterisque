package io.asterisque.core.codec;

import io.asterisque.core.Tuple;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.util.*;

/**
 * asterisque で使用されている転送可能型とアプリケーション API で受け渡しされる値の相互変換を行うクラスです。変換処理は
 * {@link Impl} サブクラスで実装されコンストラクタによってインスタンスに装備されます。インスタンスは不変でありスレッド
 * セーフです。
 * <p>
 * この変換処理は {@link io.asterisque.core.msg.Open} でのパラメータや {@link io.asterisque.core.msg.Close} での
 * 返値といったアプリケーション定義のデータ構造をエンコードする目的で使用します。
 */
public final class VariableCodec {

  /**
   * このインスタンスに定義されているコーデックの実装。先頭から順に評価し {@link Unsatisfied} を throw しなかった結果が
   * 適用される ({@link Optional} では {@code null} が返せないため)。
   */
  private final List<Impl> codecs;

  /**
   * Java の標準的な型の相互変換を行う
   * {@link com.sun.org.apache.xalan.internal.xsltc.compiler.FunctionCall.JavaType}
   * を持つインスタンスを構築します。
   */
  public VariableCodec() {
    this(new JavaTypeVariableCodec());
  }

  /**
   * 指定されたコーディックを装備した変数変換処理を構築します。後ろに配置されたコーディック実装が優先的に評価されます。
   *
   * @param impls 変数変換の実装インスタンス
   */
  public VariableCodec(@Nonnull Impl... impls) {
    this.codecs = new ArrayList<>(Arrays.asList(impls));
    Collections.reverse(this.codecs);
  }

  /**
   * アプリケーションで使用されている値を転送可能な値に変換します。
   * <p>
   * 循環参照を持つ値を指定すると StackOverflow が発生する可能性があります。
   *
   * @param value 変換する値
   * @return 転送可能な型、有効な変換ができない場合は empty()
   * @throws Unsatisfied このインスタンスでは値を変換できない場合
   */
  @Nullable
  public Object nativeToTransferable(@Nullable Object value) throws Unsatisfied {

    // 引数がすでに転送可能な場合にはそのまま返す (value == null の場合も該当するため以降は Nonnnull となる)
    if (isTransferable(value)) {
      return value;
    }

    // 配列型はここでリストに変換する
    // これはコンポーネント型ごとに class が異なり (つまり Object[].class != String[].class) TypeConversion を使用した
    // 包括的な定義が行えないため
    if (value.getClass().isArray()) {
      List<Object> list = new ArrayList<>();
      int length = Array.getLength(value);
      for (int i = 0; i < length; i++) {
        list.add(nativeToTransferable(Array.get(value, i)));
      }
      return list;
    }

    // 登録されている変換実装で最初に変換できた結果を返す
    for (Impl codec : codecs) {
      try {
        return codec.nativeToTransferable(value, this);
      } catch (Unsatisfied ignored) {
      }
    }
    throw new Unsatisfied();
  }

  /**
   * 転送可能型の値を Java の API で定義されている方に変換します。
   * {@code type} にはプリミティブ型が指定されることがあります。
   *
   * @param value 変換する値
   * @param type  求める型
   * @param <T>   型
   * @return 変換した Java 型、もしくは {@code empty()}
   * @throws Unsatisfied このインスタンスでは値を変換できない場合
   */
  @Nullable
  public <T> T transferableToNative(@Nullable Object value, @Nonnull Class<T> type) throws Unsatisfied {

    // 変換の必要が無い場合
    if (value != null && value.getClass().equals(type)) {
      return type.cast(value);
    }

    // 配列型の場合はリストから復元
    if (type.isArray() && value instanceof List) {
      @SuppressWarnings("unchecked")
      List<Object> list = (List<Object>) value;
      Class<?> ctype = type.getComponentType();
      Object array = Array.newInstance(ctype, list.size());
      for (int i = 0; i < list.size(); i++) {
        Array.set(array, i, transferableToNative(list.get(i), ctype));
      }
      return type.cast(array);
    }

    for (Impl codec : codecs) {
      try {
        return codec.transferableToNative(value, type, this);
      } catch (Unsatisfied ignored) {
      }
    }
    throw new Unsatisfied();
  }

  public interface Impl {

    /**
     * アプリケーションで使用されている値を転送可能な値に変換します。
     * このメソッドが呼び出し前に {@code value} が転送可能型以外の値を含んでいることが確認されています。つまり {@code value}
     * は {@code null} や {@code String} ではありません。
     * <p>
     * クラスのような複合型やコレクション型を再帰的に変換するにはパラメータに指定された {@code codec} を使用します。
     *
     * @param value 変換する値
     * @param codec 再帰的に変換が必要な場合に使用するコーデック
     * @return アプリケーションの使用する値から変換した転送可能な型
     * @throws Unsatisfied このインスタンスでは値を変換できない場合
     */
    Object nativeToTransferable(@Nonnull Object value, @Nonnull VariableCodec codec) throws Unsatisfied;

    /**
     * 転送可能型の値を Java の API で定義されている方に変換します。
     * {@code type} にはプリミティブ型が指定されることがあります。
     * <p>
     * クラスのような複合型やコレクション型を再帰的に変換するにはパラメータに指定された {@code codec} を使用します。
     *
     * @param value 変換する値
     * @param type  求める型
     * @param codec 再帰的に変換が必要な場合に使用するコーデック
     * @param <T>   型
     * @return 転送可能型から復元したアプリケーションの値
     * @throws Unsatisfied このインスタンスでは値を変換できない場合
     */
    <T> T transferableToNative(@Nullable Object value, @Nonnull Class<T> type, @Nonnull VariableCodec codec) throws Unsatisfied;
  }

  /**
   * 指定された値が転送可能な値かを判定します。
   *
   * @param value 判定する値
   * @return 転送可能な型の場合 true
   */
  public static boolean isTransferable(@Nullable Object value) {
    // null およびプリミティブ型と基本型
    if (value == null
        || value instanceof Boolean
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Float
        || value instanceof Double
        || value instanceof String
        || value instanceof java.math.BigInteger
        || value instanceof java.math.BigDecimal
        || value instanceof UUID) {
      return true;
    }

    // リスト型
    if (value instanceof List<?>) {
      List<?> list = (List<?>) value;
      for (Object aList : list) {
        if (!isTransferable(aList)) {
          return false;
        }
      }
      return true;
    }

    // マップ型
    if (value instanceof Map<?, ?>) {
      Map<?, ?> map = (Map<?, ?>) value;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (!isTransferable(e.getKey()) || !isTransferable(e.getValue())) {
          return false;
        }
      }
      return true;
    }

    // タプル型
    if (value instanceof Tuple) {
      Tuple tuple = (Tuple) value;
      for (int i = 0; i < tuple.count(); i++) {
        if (!isTransferable(tuple.valueAt(i))) {
          return false;
        }
      }
      return true;
    }

    // それ以外
    return false;
  }

}
