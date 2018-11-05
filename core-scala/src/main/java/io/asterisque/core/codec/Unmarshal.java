package io.asterisque.codec;


import io.asterisque.Asterisque;
import io.asterisque.Tuple;

import javax.annotation.Nonnull;
import java.util.*;

/**
 * Asterisque がサポートするデータ型をデコードします。
 * <p>
 * 各メソッドはデータの完全セットが到着したかどうかに関わらずデータを受信するたびに復元が試行されます。このため、
 * 実装クラスは対象データを復元できるだけのバイナリを受信していない場合に {@link StandardCodec.Unsatisfied}
 * を throw する必要があります。
 * <p>
 * 正常にデータを復元できた場合にバイナリのどこまでが読み込み済みなのかを知らせるため、実装クラスは
 * {@link StandardCodec#newUnmarshal(java.nio.ByteBuffer)} に渡された {@link java.nio.ByteBuffer} のバッファ位置を
 * 次の読み込み位置まで正確に移動させる必要があります。データを復元できるだけのバイナリを受信しておらず
 * {@link StandardCodec.Unsatisfied} が throw される場合は呼び出し側で位置のリセットが行われ
 * るため、サブクラス側でバッファ位置を復元する必要はありません。
 * <p>
 * 各メソッド Unsatisfied の他にメッセージのデコードに失敗した場合は {@link CodecException}
 * が発生します。
 *
 * @see StandardCodec#newUnmarshal(java.nio.ByteBuffer)
 */
public interface Unmarshal {
  default byte readTag() throws StandardCodec.Unsatisfied {
    return readInt8();
  }

  default boolean readBoolean() throws StandardCodec.Unsatisfied {
    byte tag = readTag();
    switch (tag) {
      case StandardCodec.Tag.True:
        return true;
      case StandardCodec.Tag.False:
        return false;
      default:
        throw new CodecException(String.format("unexpected boolean value: 0x%02X", tag & 0xFF));
    }
  }

  byte readInt8() throws StandardCodec.Unsatisfied;

  default short readUInt8() throws StandardCodec.Unsatisfied {
    return (short) (readInt8() & 0xFF);
  }

  short readInt16() throws StandardCodec.Unsatisfied;

  default int readUInt16() throws StandardCodec.Unsatisfied {
    return readInt16() & 0xFFFF;
  }

  int readInt32() throws StandardCodec.Unsatisfied;

  long readInt64() throws StandardCodec.Unsatisfied;

  float readFloat32() throws StandardCodec.Unsatisfied;

  double readFloat64() throws StandardCodec.Unsatisfied;

  byte[] readBinary() throws StandardCodec.Unsatisfied;

  default String readString() throws StandardCodec.Unsatisfied {
    return new String(readBinary(), Asterisque.UTF8);
  }

  default UUID readUUID() throws StandardCodec.Unsatisfied {
    long m = readInt64();
    long l = readInt64();
    return new UUID(m, l);
  }

  default List<?> readList() throws StandardCodec.Unsatisfied {
    int length = readUInt16();
    List<Object> l = new ArrayList<>(length);
    for (int i = 0; i < length; i++) {
      l.add(read());
    }
    return l;
  }

  default Map<?, ?> readMap() throws StandardCodec.Unsatisfied {
    int length = readUInt16();
    Map<Object, Object> m = new HashMap<>(length);
    for (int i = 0; i < length; i++) {
      Object key = read();
      Object value = read();
      m.put(key, value);
    }
    return m;
  }

  default Tuple readStruct() throws StandardCodec.Unsatisfied {
    String schema = readString();
    int length = readUInt8();
    Object[] values = new Object[length];
    for (int i = 0; i < length; i++) {
      values[i] = read();
    }
    return new Tuple() {
      @Override
      @Nonnull
      public String schema() {
        return schema;
      }

      @Override
      public int count() {
        return length;
      }

      @Override
      public Object valueAt(int i) {
        return values[i];
      }
    };
  }

  /**
   * 先行する {@link StandardCodec.Tag タグ} で識別される、Asterisque がサポートする任意の
   * データを読み込みます。このメソッドは {@link Marshal#write(Object)} の対になるメソッドです。
   */
  default Object read() throws StandardCodec.Unsatisfied {
    byte tag = readTag();
    switch (tag) {
      case StandardCodec.Tag.Null:
        return null;
      case StandardCodec.Tag.True:
        return Boolean.TRUE;
      case StandardCodec.Tag.False:
        return Boolean.FALSE;
      case StandardCodec.Tag.Int8:
        return readInt8();
      case StandardCodec.Tag.Int16:
        return readInt16();
      case StandardCodec.Tag.Int32:
        return readInt32();
      case StandardCodec.Tag.Int64:
        return readInt64();
      case StandardCodec.Tag.Float32:
        return readFloat32();
      case StandardCodec.Tag.Float64:
        return readFloat64();
      case StandardCodec.Tag.Binary:
        return readBinary();
      case StandardCodec.Tag.String:
        return readString();
      case StandardCodec.Tag.UUID:
        return readUUID();
      case StandardCodec.Tag.List:
        return readList();
      case StandardCodec.Tag.Map:
        return readMap();
      case StandardCodec.Tag.Struct:
        return readStruct();
      default:
        throw new CodecException(String.format("unexpected value tag: %02X", tag & 0xFF));
    }
  }
}
