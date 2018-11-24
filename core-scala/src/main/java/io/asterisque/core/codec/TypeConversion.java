package io.asterisque.core.codec;

import io.asterisque.core.Tuple;

import javax.annotation.Nonnull;
import java.util.*;

/**
 * 特定の型に対して転送可能型への変換を実装するためのインターフェースです。
 *
 * @param <T> 転送可能型への変換を行う型
 */
public abstract class TypeConversion<T> {
  public final String typeName;

  protected TypeConversion(@Nonnull String typeName) {
    this.typeName = typeName;
  }

  public abstract Object toTransferable(@Nonnull T value, @Nonnull VariableCodec codec) throws Unsatisfied;

  public T fromNull() throws Unsatisfied {
    throw new Unsatisfied();
  }

  public T fromBoolean(boolean b) throws Unsatisfied {
    throw new Unsatisfied();
  }

  public T fromInt8(byte b) throws Unsatisfied {
    throw new Unsatisfied();
  }

  public T fromInt16(short b) throws Unsatisfied {
    throw new Unsatisfied();
  }

  public T fromInt32(int i) throws Unsatisfied {
    throw new Unsatisfied();
  }

  public T fromInt64(long i) throws Unsatisfied {
    throw new Unsatisfied();
  }

  public T fromFloat32(float f) throws Unsatisfied {
    throw new Unsatisfied();
  }

  public T fromFloat64(double f) throws Unsatisfied {
    throw new Unsatisfied();
  }

  public T fromBytes(@Nonnull byte[] b) throws Unsatisfied {
    throw new Unsatisfied();
  }

  public T fromString(@Nonnull String s) throws Unsatisfied {
    throw new Unsatisfied();
  }

  public T fromUUID(@Nonnull UUID uuid) throws Unsatisfied {
    throw new Unsatisfied();
  }

  public T fromList(@Nonnull List<Object> list, @Nonnull VariableCodec codec) throws Unsatisfied {
    throw new Unsatisfied();
  }

  public T fromMap(@Nonnull Map<Object, Object> map, @Nonnull VariableCodec codec) throws Unsatisfied {
    throw new Unsatisfied();
  }

  public T fromTuple(@Nonnull Tuple tuple, @Nonnull VariableCodec codec) throws Unsatisfied {
    throw new Unsatisfied();
  }

}
