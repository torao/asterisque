package io.asterisque.core.codec;


import io.asterisque.Asterisque;
import io.asterisque.core.Tuple;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * asterisque がサポートするデータ型をデコードします。
 * <p>
 * 各メソッドはデータの完全セットが到着したかどうかに関わらずデータを受信するたびに復元が試行されます。このため、
 * 実装クラスは対象データを復元できるだけのバイナリを受信していない場合に {@link Unsatisfied}
 * を throw する必要があります。
 * <p>
 * 正常にデータを復元できた場合にバイナリのどこまでが読み込み済みなのかを知らせるため、実装クラスは
 * {@link MessageFieldCodec#newUnmarshal(java.nio.ByteBuffer)} に渡された {@link java.nio.ByteBuffer} のバッファ位置を
 * 次の読み込み位置まで正確に移動させる必要があります。データを復元できるだけのバイナリを受信しておらず
 * {@link Unsatisfied} が throw される場合は呼び出し側で位置のリセットが行われ
 * るため、サブクラス側でバッファ位置を復元する必要はありません。
 * <p>
 * 各メソッド Unsatisfied の他にメッセージのデコードに失敗した場合は {@link CodecException} が発生します。
 *
 * @see MessageFieldCodec#newUnmarshal(java.nio.ByteBuffer)
 */
public interface Unmarshal {

  default byte readTag() throws Unsatisfied {
    return readInt8();
  }

  default boolean readBoolean() throws Unsatisfied {
    byte tag = readTag();
    switch (tag) {
      case MessageFieldCodec.Tag.True:
        return true;
      case MessageFieldCodec.Tag.False:
        return false;
      default:
        throw new CodecException(String.format("unexpected boolean value: 0x%02X", tag & 0xFF));
    }
  }

  byte readInt8() throws Unsatisfied;

  default short readUInt8() throws Unsatisfied {
    return (short) (readInt8() & 0xFF);
  }

  short readInt16() throws Unsatisfied;

  default int readUInt16() throws Unsatisfied {
    return readInt16() & 0xFFFF;
  }

  int readInt32() throws Unsatisfied;

  long readInt64() throws Unsatisfied;

  float readFloat32() throws Unsatisfied;

  double readFloat64() throws Unsatisfied;

  @Nonnull
  byte[] readBinary() throws Unsatisfied;

  @Nonnull
  default String readString() throws Unsatisfied {
    return new String(readBinary(), Asterisque.UTF8);
  }

  @Nonnull
  default UUID readUUID() throws Unsatisfied {
    long m = readInt64();
    long l = readInt64();
    return new UUID(m, l);
  }

  @Nonnull
  default List<?> readList() throws Unsatisfied {
    int length = readUInt16();
    List<Object> l = new ArrayList<>(length);
    for (int i = 0; i < length; i++) {
      l.add(read());
    }
    return l;
  }

  @Nonnull
  default Map<?, ?> readMap() throws Unsatisfied {
    int length = readUInt16();
    Map<Object, Object> m = new HashMap<>(length);
    for (int i = 0; i < length; i++) {
      Object key = read();
      Object value = read();
      m.put(key, value);
    }
    return m;
  }

  @Nonnull
  default Tuple readTuple() throws Unsatisfied {
    int length = readUInt8();
    Object[] values = new Object[length];
    for (int i = 0; i < length; i++) {
      values[i] = read();
    }
    return Tuple.of(values);
  }

  /**
   * 先行する {@link MessageFieldCodec.Tag タグ} で識別される、Asterisque がサポートする任意の
   * データを読み込みます。このメソッドは {@link Marshal#write(Object)} の対になるメソッドです。
   */
  @Nullable
  default Object read() throws Unsatisfied {
    byte tag = readTag();
    switch (tag) {
      case MessageFieldCodec.Tag.Null:
        return null;
      case MessageFieldCodec.Tag.True:
        return Boolean.TRUE;
      case MessageFieldCodec.Tag.False:
        return Boolean.FALSE;
      case MessageFieldCodec.Tag.Int8:
        return readInt8();
      case MessageFieldCodec.Tag.Int16:
        return readInt16();
      case MessageFieldCodec.Tag.Int32:
        return readInt32();
      case MessageFieldCodec.Tag.Int64:
        return readInt64();
      case MessageFieldCodec.Tag.Float32:
        return readFloat32();
      case MessageFieldCodec.Tag.Float64:
        return readFloat64();
      case MessageFieldCodec.Tag.Binary:
        return readBinary();
      case MessageFieldCodec.Tag.String:
        return readString();
      case MessageFieldCodec.Tag.UUID:
        return readUUID();
      case MessageFieldCodec.Tag.List:
        return readList();
      case MessageFieldCodec.Tag.Map:
        return readMap();
      case MessageFieldCodec.Tag.Tuple:
        return readTuple();
      default:
        throw new CodecException(String.format("unexpected value tag: %02X", tag & 0xFF));
    }
  }
}
