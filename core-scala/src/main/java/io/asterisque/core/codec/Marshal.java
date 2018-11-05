package io.asterisque.codec;


import io.asterisque.Asterisque;
import io.asterisque.Tuple;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;

interface Marshal {
  ByteBuffer toByteBuffer();

  default void writeTag(byte tag) {
    writeInt8(tag);
  }

  default void writeTrue() {
    writeTag(StandardCodec.Tag.True);
  }

  default void writeFalse() {
    writeTag(StandardCodec.Tag.False);
  }

  void writeInt8(byte i);

  default void writeUInt8(short i) {
    if (i < 0 || i > 0xFF) {
      throw new IllegalArgumentException(String.format("out of unsigned int16 range: %d", i));
    }
    writeInt8((byte) (i & 0xFF));
  }

  void writeInt16(short i);

  default void writeUInt16(int i) {
    if (i < 0 || i > 0xFFFF) {
      throw new IllegalArgumentException(String.format("out of unsigned int16 range: %d", i));
    }
    writeInt16((short) (i & 0xFFFF));
  }

  void writeInt32(int i);

  void writeInt64(long i);

  void writeFloat32(float i);

  void writeFloat64(double i);

  default void writeBinary(byte[] b) {
    writeBinary(b, 0, b.length);
  }

  void writeBinary(byte[] b, int offset, int length);

  default void writeString(String s) {
    writeBinary(s.getBytes(Asterisque.UTF8));
  }

  default void writeUUID(UUID u) {
    writeInt64(u.getMostSignificantBits());
    writeInt64(u.getLeastSignificantBits());
  }

  default void writeList(List<?> l) {
    writeUInt16(l.size());
    for (Object x : l) {
      write(x);
    }
  }

  default void writeMap(Map<?, ?> m) {
    writeUInt16(m.size());
    for (Map.Entry<?, ?> e : m.entrySet()) {
      write(e.getKey());
      write(e.getValue());
    }
  }

  default void writeStruct(Tuple b) {
    if (b.count() > Tuple.MaxFields) {
      throw new IllegalArgumentException(
          "field count of " + b.getClass().getName() + " is too large: " + b.count() + " / " + Tuple.MaxFields);
    }
    writeString(b.schema());
    writeUInt8((short) (b.count() & 0xFF));
    for (int i = 0; i < b.count(); i++) {
      write(b.valueAt(i));
    }
  }
  // ============================================================================================

  /**
   * Asterisque がサポートする任意のデータを {@link StandardCodec.Tag タグ} 付きで書き込み
   * ます。このメソッドは {@link Unmarshal#read()} の対になるメソッドです。
   */
  default void write(Object value) {
    value = TypeConversion.toTransfer(value);
    assert (TypeConversion.isDefaultSafeValue(value));
    if (value == null) {
      writeTag(StandardCodec.Tag.Null);
    } else if (value instanceof Boolean) {
      if ((Boolean) value) {
        writeTag(StandardCodec.Tag.True);
      } else {
        writeTag(StandardCodec.Tag.False);
      }
    } else if (value instanceof Byte) {
      writeTag(StandardCodec.Tag.Int8);
      writeInt8((Byte) value);
    } else if (value instanceof Short) {
      writeTag(StandardCodec.Tag.Int16);
      writeInt16((Short) value);
    } else if (value instanceof Integer) {
      writeTag(StandardCodec.Tag.Int32);
      writeInt32((Integer) value);
    } else if (value instanceof Long) {
      writeTag(StandardCodec.Tag.Int64);
      writeInt64((Long) value);
    } else if (value instanceof Float) {
      writeTag(StandardCodec.Tag.Float32);
      writeFloat32((Float) value);
    } else if (value instanceof Double) {
      writeTag(StandardCodec.Tag.Float64);
      writeFloat64((Double) value);
    } else if (value instanceof byte[]) {
      writeTag(StandardCodec.Tag.Binary);
      writeBinary((byte[]) value);
    } else if (value instanceof Character) {
      writeTag(StandardCodec.Tag.String);
      writeString(value.toString());
    } else if (value instanceof String) {
      writeTag(StandardCodec.Tag.String);
      writeString((String) value);
    } else if (value instanceof UUID) {
      writeTag(StandardCodec.Tag.UUID);
      writeUUID((UUID) value);
    } else if (value instanceof List<?>) {
      writeTag(StandardCodec.Tag.List);
      writeList((List<?>) value);
    } else if (value instanceof Map<?, ?>) {
      writeTag(StandardCodec.Tag.Map);
      writeMap((Map<?, ?>) value);
    } else if (value instanceof Tuple) {
      writeTag(StandardCodec.Tag.Struct);
      writeStruct((Tuple) value);
    } else {
      throw new CodecException(String.format("marshal not supported for data type: %s: %s", value.getClass().getCanonicalName(), value));
    }
  }
}
