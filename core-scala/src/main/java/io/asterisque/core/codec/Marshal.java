package io.asterisque.core.codec;


import io.asterisque.Asterisque;
import io.asterisque.core.Tuple;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 転送可能型の値に対してメモリ上のバッファに書き込みを行い、バイナリを生成するための機能を実装するインターフェース。
 * メッセージのフィールドごとに明確なフォーマットを定義する {@link MessageFieldCodec} によって使用されます。
 */
public interface Marshal {

  /**
   * すべてのフィールドを書き終えた後に {@link ByteBuffer} を参照するために呼び出されます。サブクラスは書き込んだ内容を
   * flip して返す必要があります。
   *
   * @return すべてのフィールドを書き込んだバイナリ
   */
  @Nonnull
  ByteBuffer toByteBuffer();

  default void writeTag(byte tag) {
    writeInt8(tag);
  }

  default void writeTrue() {
    writeTag(MessageFieldCodec.Tag.True);
  }

  default void writeFalse() {
    writeTag(MessageFieldCodec.Tag.False);
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

  default void writeBinary(@Nonnull byte[] b) {
    writeBinary(b, 0, b.length);
  }

  void writeBinary(@Nonnull byte[] b, int offset, int length);

  default void writeString(@Nonnull String s) {
    writeBinary(s.getBytes(Asterisque.UTF8));
  }

  default void writeUUID(@Nonnull UUID u) {
    writeInt64(u.getMostSignificantBits());
    writeInt64(u.getLeastSignificantBits());
  }

  default void writeList(@Nonnull List<?> l) {
    writeUInt16(l.size());
    for (Object x : l) {
      write(x);
    }
  }

  default void writeMap(@Nonnull Map<?, ?> m) {
    writeUInt16(m.size());
    for (Map.Entry<?, ?> e : m.entrySet()) {
      write(e.getKey());
      write(e.getValue());
    }
  }

  default void writeTuple(@Nonnull Tuple b) {
    if (b.count() > Tuple.MaxFields) {
      throw new IllegalArgumentException(
          "field count of " + b.getClass().getName() + " is too large: " + b.count() + " / " + Tuple.MaxFields);
    }
    writeUInt8((short) (b.count() & 0xFF));
    for (int i = 0; i < b.count(); i++) {
      write(b.valueAt(i));
    }
  }

  /**
   * asterisque がサポートする任意のデータを {@link MessageFieldCodec.Tag タグ} 付きで書き込みます。このメソッドは
   * {@link Unmarshal#read()} の対になるメソッドです。
   *
   * @param value 転送可能型の値
   */
  default void write(@Nullable Object value) {
    assert(VariableCodec.isTransferable(value));
    if (value == null) {
      writeTag(MessageFieldCodec.Tag.Null);
    } else if (value instanceof Boolean) {
      if ((Boolean) value) {
        writeTag(MessageFieldCodec.Tag.True);
      } else {
        writeTag(MessageFieldCodec.Tag.False);
      }
    } else if (value instanceof Byte) {
      writeTag(MessageFieldCodec.Tag.Int8);
      writeInt8((Byte) value);
    } else if (value instanceof Short) {
      writeTag(MessageFieldCodec.Tag.Int16);
      writeInt16((Short) value);
    } else if (value instanceof Integer) {
      writeTag(MessageFieldCodec.Tag.Int32);
      writeInt32((Integer) value);
    } else if (value instanceof Long) {
      writeTag(MessageFieldCodec.Tag.Int64);
      writeInt64((Long) value);
    } else if (value instanceof Float) {
      writeTag(MessageFieldCodec.Tag.Float32);
      writeFloat32((Float) value);
    } else if (value instanceof Double) {
      writeTag(MessageFieldCodec.Tag.Float64);
      writeFloat64((Double) value);
    } else if (value instanceof byte[]) {
      writeTag(MessageFieldCodec.Tag.Binary);
      writeBinary((byte[]) value);
    } else if (value instanceof Character) {
      writeTag(MessageFieldCodec.Tag.String);
      writeString(value.toString());
    } else if (value instanceof String) {
      writeTag(MessageFieldCodec.Tag.String);
      writeString((String) value);
    } else if (value instanceof UUID) {
      writeTag(MessageFieldCodec.Tag.UUID);
      writeUUID((UUID) value);
    } else if (value instanceof List<?>) {
      writeTag(MessageFieldCodec.Tag.List);
      writeList((List<?>) value);
    } else if (value instanceof Map<?, ?>) {
      writeTag(MessageFieldCodec.Tag.Map);
      writeMap((Map<?, ?>) value);
    } else if (value instanceof Tuple) {
      writeTag(MessageFieldCodec.Tag.Tuple);
      writeTuple((Tuple) value);
    } else {
      throw new CodecException(String.format(
          "marshal not supported for data type: %s: %s", value.getClass().getCanonicalName(), value));
    }
  }

}
