package io.asterisque.core.codec;

import io.asterisque.core.Debug;
import io.asterisque.core.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * asterisque における転送可能型とアプリケーション API で使用する型との相互変換を実装するクラス。
 * インスタンスは不変でありスレッド間で共有することができます。
 */
public abstract class TypeVariableCodec implements VariableCodec.Impl {
  private static final Logger logger = LoggerFactory.getLogger(TypeVariableCodec.class);

  private final Map<Class<?>, TypeConversion<?>> types = new HashMap<>();

  protected TypeVariableCodec(@Nonnull Map<Class<?>, TypeConversion<?>> types) {
    for (Map.Entry<Class<?>, TypeConversion<?>> e : types.entrySet()) {
      this.types.put(e.getKey(), e.getValue());
    }
  }

  @Override
  public Object nativeToTransferable(@Nonnull Object value, @Nonnull VariableCodec codec) throws Unsatisfied {
    Optional<TypeConversion> opt = Optional.ofNullable(types.get(value.getClass()));
    if (opt.isPresent()) {
      @SuppressWarnings("unchecked")
      TypeConversion<Object> conv = (TypeConversion<Object>) opt.get();
      return conv.toTransferable(value, codec);
    }
    throw new Unsatisfied(Debug.toString(value));
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T transferableToNative(@Nullable Object transferable, @Nonnull Class<T> type, @Nonnull VariableCodec codec) throws Unsatisfied {
    Optional<TypeConversion> opt = Optional.ofNullable(types.get(type));
    if (!opt.isPresent()) {
      throw new Unsatisfied();
    }
    @SuppressWarnings("unchecked")
    TypeConversion<T> conv = (TypeConversion<T>) opt.get();
    if (transferable == null) {
      return conv.fromNull();
    }
    if (transferable instanceof Boolean) {
      return conv.fromBoolean((Boolean) transferable);
    }
    if (transferable instanceof Byte) {
      return conv.fromInt8((Byte) transferable);
    }
    if (transferable instanceof Short) {
      return conv.fromInt16((Short) transferable);
    }
    if (transferable instanceof Integer) {
      return conv.fromInt32((Integer) transferable);
    }
    if (transferable instanceof Long) {
      return conv.fromInt64((Long) transferable);
    }
    if (transferable instanceof Float) {
      return conv.fromFloat32((Float) transferable);
    }
    if (transferable instanceof Double) {
      return conv.fromFloat64((Double) transferable);
    }
    if (transferable instanceof byte[]) {
      return conv.fromBytes((byte[]) transferable);
    }
    if (transferable instanceof String) {
      return conv.fromString((String) transferable);
    }
    if (transferable instanceof UUID) {
      return conv.fromUUID((UUID) transferable);
    }
    if (transferable instanceof List) {
      return conv.fromList((List<Object>) transferable, codec);
    }
    if (transferable instanceof Map) {
      return conv.fromMap((Map<Object, Object>) transferable, codec);
    }
    if (transferable instanceof Tuple) {
      return conv.fromTuple((Tuple) transferable, codec);
    }
    throw new Unsatisfied(transferable.getClass().getCanonicalName() + ": " + Debug.toString(transferable));
  }

}
