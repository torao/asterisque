package io.asterisque.core.codec;

import io.asterisque.core.Tuple;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * asterisque における転送可能型と Java の言語機能とコアライブラリでサポートされている型とでの相互変換を行うためのクラス。
 */
public class JavaTypeVariableCodec extends TypeVariableCodec {

  public JavaTypeVariableCodec() {
    super(new HashMap<Class<?>, TypeConversion<?>>() {{
      put(Void.TYPE, new VoidTypeConversion());
      put(Boolean.TYPE, new BooleanTypeConversion());
      put(Byte.TYPE, new ByteTypeConversion());
      put(Short.TYPE, new ShortTypeConversion());
      put(Integer.TYPE, new IntTypeConversion());
      put(Long.TYPE, new LongTypeConversion());
      put(Float.TYPE, new FloatTypeConversion());
      put(Double.TYPE, new DoubleTypeConversion());
      put(Character.TYPE, new CharTypeConversion());

      put(Void.class, new VoidTypeConversion());
      put(Boolean.class, new BooleanTypeConversion());
      put(Byte.class, new ByteTypeConversion());
      put(Short.class, new ShortTypeConversion());
      put(Integer.class, new IntTypeConversion());
      put(Long.class, new LongTypeConversion());
      put(Float.class, new FloatTypeConversion());
      put(Double.class, new DoubleTypeConversion());
      put(Character.class, new CharTypeConversion());

      // プリミティブ型やオブジェクト型の配列とリストとの変換は VariableCodec で行っている。
      // byte[] と char[] がプリミティブ型配列で list 化されない。byte[] は転送可能型なため char[] のみ実装
      put(char[].class, new CharArrayTypeConversion());

      put(String.class, new StringTypeConversion());
      put(UUID.class, new UUIDTypeConversion());

      put(Set.class, new SetTypeConversion());
      put(Date.class, new DateTypeConversion());
    }});
  }

  private static abstract class TransferableTypeConversion<U> extends TypeConversion<U> {
    private TransferableTypeConversion(@Nonnull String typeName) {
      super(typeName);
    }

    @Override
    public Object toTransferable(@Nonnull U value, @Nonnull VariableCodec codec) {
      return value;
    }
  }

  private static class VoidTypeConversion extends TypeConversion<Void> {
    private VoidTypeConversion() {
      super("void");
    }

    @Override
    public Object toTransferable(@Nonnull Void value, @Nonnull VariableCodec codec) {
      return null;
    }

    public Void fromNull() {
      return null;
    }

    public Void fromBoolean(boolean b) {
      return null;
    }

    public Void fromInt8(byte b) {
      return null;
    }

    public Void fromInt16(short b) {
      return null;
    }

    public Void fromInt32(int i) {
      return null;
    }

    public Void fromInt64(long i) {
      return null;
    }

    public Void fromFloat32(float f) {
      return null;
    }

    public Void fromFloat64(double f) {
      return null;
    }

    public Void fromBytes(@Nonnull byte[] b) {
      return null;
    }

    public Void fromString(@Nonnull String s) {
      return null;
    }

    public Void fromUUID(@Nonnull UUID uuid) {
      return null;
    }

    public Void fromList(@Nonnull List<Object> list, @Nonnull VariableCodec codec) {
      return null;
    }

    public Void fromMap(@Nonnull Map<Object, Object> map, @Nonnull VariableCodec codec) {
      return null;
    }

    public Void fromTuple(@Nonnull Tuple tuple, @Nonnull VariableCodec codec) {
      return null;
    }
  }

  private static class BooleanTypeConversion extends TransferableTypeConversion<Boolean> {
    private BooleanTypeConversion() {
      super("boolean");
    }

    @Override
    public Boolean fromNull() {
      return false;
    }

    @Override
    public Boolean fromBoolean(boolean i) {
      return i;
    }

    @Override
    public Boolean fromInt8(byte i) {
      return i != 0;
    }

    @Override
    public Boolean fromInt16(short i) {
      return i != 0;
    }

    @Override
    public Boolean fromInt32(int i) {
      return i != 0;
    }

    @Override
    public Boolean fromInt64(long i) {
      return i != 0;
    }

    @Override
    public Boolean fromFloat32(float f) {
      return f != 0.0 && Float.isFinite(f);
    }

    @Override
    public Boolean fromFloat64(double f) {
      return f != 0.0 && Double.isFinite(f);
    }

    @Override
    public Boolean fromString(@Nonnull String s) {
      return Boolean.valueOf(s);
    }
  }

  private static class ByteTypeConversion extends TransferableTypeConversion<Byte> {
    private ByteTypeConversion() {
      super("byte");
    }

    @Override
    public Byte fromNull() {
      return (byte) 0;
    }

    @Override
    public Byte fromBoolean(boolean i) {
      return (byte) (i ? 1 : 0);
    }

    @Override
    public Byte fromInt8(byte i) {
      return i;
    }

    @Override
    public Byte fromInt16(short i) {
      return (byte) i;
    }

    @Override
    public Byte fromInt32(int i) {
      return (byte) i;
    }

    @Override
    public Byte fromInt64(long i) {
      return (byte) i;
    }

    @Override
    public Byte fromFloat32(float f) {
      return (byte) f;
    }

    @Override
    public Byte fromFloat64(double f) {
      return (byte) f;
    }

    @Override
    public Byte fromString(@Nonnull String s) throws Unsatisfied {
      try {
        return Byte.valueOf(s);
      } catch (NumberFormatException ex) {
        throw new Unsatisfied();
      }
    }
  }

  private static class ShortTypeConversion extends TransferableTypeConversion<Short> {
    private ShortTypeConversion() {
      super("short");
    }

    @Override
    public Short fromNull() {
      return (byte) 0;
    }

    @Override
    public Short fromBoolean(boolean i) {
      return (short) (i ? 1 : 0);
    }

    @Override
    public Short fromInt8(byte i) {
      return (short) i;
    }

    @Override
    public Short fromInt16(short i) {
      return i;
    }

    @Override
    public Short fromInt32(int i) {
      return (short) i;
    }

    @Override
    public Short fromInt64(long i) {
      return (short) i;
    }

    @Override
    public Short fromFloat32(float f) {
      return (short) f;
    }

    @Override
    public Short fromFloat64(double f) {
      return (short) f;
    }

    @Override
    public Short fromString(@Nonnull String s) throws Unsatisfied {
      try {
        return Short.valueOf(s);
      } catch (NumberFormatException ex) {
        throw new Unsatisfied();
      }
    }
  }

  private static class IntTypeConversion extends TransferableTypeConversion<Integer> {
    private IntTypeConversion() {
      super("int");
    }

    @Override
    public Integer fromNull() {
      return 0;
    }

    @Override
    public Integer fromBoolean(boolean i) {
      return i ? 1 : 0;
    }

    @Override
    public Integer fromInt8(byte i) {
      return (int) i;
    }

    @Override
    public Integer fromInt16(short i) {
      return (int) i;
    }

    @Override
    public Integer fromInt32(int i) {
      return i;
    }

    @Override
    public Integer fromInt64(long i) {
      return (int) i;
    }

    @Override
    public Integer fromFloat32(float f) {
      return (int) f;
    }

    @Override
    public Integer fromFloat64(double f) {
      return (int) f;
    }

    @Override
    public Integer fromString(@Nonnull String s) throws Unsatisfied {
      try {
        return Integer.valueOf(s);
      } catch (NumberFormatException ex) {
        throw new Unsatisfied();
      }
    }
  }

  private static class LongTypeConversion extends TransferableTypeConversion<Long> {
    private LongTypeConversion() {
      super("long");
    }

    @Override
    public Long fromNull() {
      return (long) 0;
    }

    @Override
    public Long fromBoolean(boolean i) {
      return (long) (i ? 1 : 0);
    }

    @Override
    public Long fromInt8(byte i) {
      return (long) i;
    }

    @Override
    public Long fromInt16(short i) {
      return (long) i;
    }

    @Override
    public Long fromInt32(int i) {
      return (long) i;
    }

    @Override
    public Long fromInt64(long i) {
      return i;
    }

    @Override
    public Long fromFloat32(float f) {
      return (long) f;
    }

    @Override
    public Long fromFloat64(double f) {
      return (long) f;
    }

    @Override
    public Long fromString(@Nonnull String s) throws Unsatisfied {
      try {
        return Long.valueOf(s);
      } catch (NumberFormatException ex) {
        throw new Unsatisfied();
      }
    }
  }

  private static class FloatTypeConversion extends TransferableTypeConversion<Float> {
    private FloatTypeConversion() {
      super("float");
    }

    @Override
    public Float fromNull() {
      return 0.0f;
    }

    @Override
    public Float fromBoolean(boolean i) {
      return i ? 1.0f : 0.0f;
    }

    @Override
    public Float fromInt8(byte i) {
      return (float) i;
    }

    @Override
    public Float fromInt16(short i) {
      return (float) i;
    }

    @Override
    public Float fromInt32(int i) {
      return (float) i;
    }

    @Override
    public Float fromInt64(long i) {
      return (float) i;
    }

    @Override
    public Float fromFloat32(float f) {
      return f;
    }

    @Override
    public Float fromFloat64(double f) {
      return (float) f;
    }

    @Override
    public Float fromString(@Nonnull String s) throws Unsatisfied {
      try {
        return Float.valueOf(s);
      } catch (NumberFormatException ex) {
        throw new Unsatisfied();
      }
    }
  }

  private static class DoubleTypeConversion extends TransferableTypeConversion<Double> {
    private DoubleTypeConversion() {
      super("double");
    }

    @Override
    public Double fromNull() {
      return 0.0;
    }

    @Override
    public Double fromBoolean(boolean i) {
      return i ? 1.0 : 0.0;
    }

    @Override
    public Double fromInt8(byte i) {
      return (double) i;
    }

    @Override
    public Double fromInt16(short i) {
      return (double) i;
    }

    @Override
    public Double fromInt32(int i) {
      return (double) i;
    }

    @Override
    public Double fromInt64(long i) {
      return (double) i;
    }

    @Override
    public Double fromFloat32(float f) {
      return (double) f;
    }

    @Override
    public Double fromFloat64(double f) {
      return f;
    }

    @Override
    public Double fromString(@Nonnull String s) throws Unsatisfied {
      try {
        return Double.valueOf(s);
      } catch (NumberFormatException ex) {
        throw new Unsatisfied();
      }
    }
  }

  private static class StringTypeConversion extends TransferableTypeConversion<String> {
    private StringTypeConversion() {
      super("string");
    }

    @Override
    public String fromNull() {
      return null;
    }

    @Override
    public String fromBoolean(boolean i) {
      return String.valueOf(i);
    }

    @Override
    public String fromInt8(byte i) {
      return String.valueOf(i);
    }

    @Override
    public String fromInt16(short i) {
      return String.valueOf(i);
    }

    @Override
    public String fromInt32(int i) {
      return String.valueOf(i);
    }

    @Override
    public String fromInt64(long i) {
      return String.valueOf(i);
    }

    @Override
    public String fromFloat32(float f) {
      return String.valueOf(f);
    }

    @Override
    public String fromFloat64(double f) {
      return String.valueOf(f);
    }

    @Override
    public String fromString(@Nonnull String s) {
      return s;
    }

    @Override
    public String fromBytes(@Nonnull byte[] b) {
      return new String(b, StandardCharsets.UTF_8);
    }

    @Override
    public String fromUUID(@Nonnull UUID uuid) {
      return String.valueOf(uuid);
    }
  }

  private static class UUIDTypeConversion extends TransferableTypeConversion<UUID> {
    private UUIDTypeConversion() {
      super("uuid");
    }

    @Override
    public UUID fromNull() {
      return null;
    }

    @Override
    public UUID fromString(@Nonnull String s) {
      return UUID.fromString(s);
    }

    @Override
    public UUID fromUUID(@Nonnull UUID uuid) {
      return uuid;
    }
  }

  private static class CharTypeConversion extends TypeConversion<Character> {
    private CharTypeConversion() {
      super("char");
    }

    @Override
    public Object toTransferable(@Nonnull Character ch, @Nonnull VariableCodec codec) {
      return String.valueOf(ch);
    }

    @Override
    public Character fromNull() {
      return '\u0000';
    }

    @Override
    public Character fromInt8(byte i) {
      return (char) i;
    }

    @Override
    public Character fromInt16(short i) {
      return (char) i;
    }

    @Override
    public Character fromInt32(int i) {
      return (char) i;
    }

    @Override
    public Character fromInt64(long i) {
      return (char) i;
    }

    @Override
    public Character fromFloat32(float f) {
      return (char) f;
    }

    @Override
    public Character fromFloat64(double f) {
      return (char) f;
    }

    @Override
    public Character fromString(@Nonnull String s) {
      return s.length() == 0 ? '\u0000' : s.charAt(0);
    }
  }

  private static class CharArrayTypeConversion extends TypeConversion<char[]> {
    private CharArrayTypeConversion() {
      super("char[]");
    }

    @Override
    public Object toTransferable(@Nonnull char[] b, @Nonnull VariableCodec codec) {
      return new String(b);
    }

    @Override
    public char[] fromString(@Nonnull String s) {
      return s.toCharArray();
    }

    @Override
    public char[] fromList(@Nonnull List<Object> list, @Nonnull VariableCodec codec) throws Unsatisfied {
      char[] array = new char[list.size()];
      for (int i = 0; i < list.size(); i++) {
        array[i] = Optional.ofNullable(codec.transferableToNative(list.get(i), Character.class))
            .orElseThrow(Unsatisfied::new);
      }
      return array;
    }
  }

  private static class SetTypeConversion extends TypeConversion<Set<Object>> {
    private SetTypeConversion() {
      super("set");
    }

    @Override
    public Object toTransferable(@Nonnull Set<Object> b, @Nonnull VariableCodec codec) throws Unsatisfied {
      List<Object> list = new ArrayList<>();
      for (Object obj : b) {
        list.add(codec.nativeToTransferable(obj));
      }
      return list;
    }

    @Override
    public Set<Object> fromList(@Nonnull List<Object> list, @Nonnull VariableCodec codec) {
      return new HashSet<>(list);
    }
  }

  private static class DateTypeConversion extends TypeConversion<Date> {
    private static final DateTimeFormatter[] formatters = new DateTimeFormatter[]{
        DateTimeFormatter.BASIC_ISO_DATE
    };

    private DateTypeConversion() {
      super("date");
    }

    @Override
    public Object toTransferable(@Nonnull Date b, @Nonnull VariableCodec codec) {
      return b.getTime();
    }

    @Override
    public Date fromInt64(long value) {
      return new Date(value);
    }

    @Override
    public Date fromString(@Nonnull String value) throws Unsatisfied {
      for (DateTimeFormatter fmt : formatters) {
        try {
          return new Date(Instant.from(fmt.parse(value)).toEpochMilli());
        } catch (DateTimeParseException ignored) {
        }
      }
      throw new Unsatisfied();
    }
  }

}
