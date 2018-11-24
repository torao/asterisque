package io.asterisque.core;

import javax.annotation.Nonnull;

public abstract class Struct extends Tuple {

  /**
   * このタプルの表すスキーマ名です。Java 系の言語ではクラス名に相当します。
   */
  public final String schema;

  Struct(@Nonnull String schema, @Nonnull Object[] values) {
    super(values);
    this.schema = schema;
  }

}
