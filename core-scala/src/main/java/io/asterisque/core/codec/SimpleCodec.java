package io.asterisque.codec;

import javax.annotation.Nonnull;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * {@link java.nio.ByteBuffer} を使用した非圧縮形式のメッセージシリアライゼイションを行います。
 * バイト順序は {@link java.nio.ByteOrder#BIG_ENDIAN ビッグエンディアン} となります。
 *
 * @author Takami Torao
 */
public class SimpleCodec extends StandardCodec {

  /**
   * SimpleCodec は Singleton で使用します。
   */
  private static final SimpleCodec Instance = new SimpleCodec();

  /**
   * SimpleCodec は Singleton で使用します。
   */
  public static SimpleCodec getInstance(){
    return Instance;
  }

  /**
   * コンストラクタは何も行いません。
   */
  private SimpleCodec() { }

  /**
   * 直列化処理を参照します。
   */
  @Nonnull
  public Marshal newMarshal(){
    return new SimpleMarshal();
  }

  /**
   * 非直列化処理を参照します。
   */
  @Nonnull
  public Unmarshal newUnmarshal(@Nonnull ByteBuffer buffer){
    return new SimpleUnmarshal(buffer);
  }

  /**
   * 各転送可能型に対してビッグエンディアンでシリアライズを行います。
   */
  private static class SimpleMarshal implements Marshal {
    private ByteBuffer buffer = ByteBuffer.allocate(64);
    private SimpleMarshal(){
      buffer.order(ByteOrder.BIG_ENDIAN);
    }
    public ByteBuffer toByteBuffer() {
      buffer.flip();
      return buffer;
    }
    public void writeInt8(byte i){
      ensureCapacity(Byte.BYTES);
      buffer.put(i);
    }
    public void writeInt16(short i){
      ensureCapacity(Short.BYTES);
      buffer.putShort(i);
    }
    public void writeInt32(int i) {
      ensureCapacity(Integer.BYTES);
      buffer.putInt(i);
    }
    public void writeInt64(long i) {
      ensureCapacity(Long.BYTES);
      buffer.putLong(i);
    }
    public void writeFloat32(float i) {
      ensureCapacity(Float.BYTES);
      buffer.putFloat(i);
    }
    public void writeFloat64(double i){
      ensureCapacity(Double.BYTES);
      buffer.putDouble(i);
    }
    public void writeBinary(byte[] b, int offset, int length){
      if(length < 0 || length > 0xFFFF){
        throw new CodecException(String.format("too large binary: %d", length));
      }
      ensureCapacity(Short.BYTES + length);
      buffer.putShort((short) length);
      buffer.put(b, offset, length);
    }
    private void ensureCapacity(int size){
      if(buffer.remaining() < size){
        int min = buffer.remaining() + size;
        int newSize = buffer.capacity() * 2;
        while(newSize < min){
          newSize *= 2;
        }
        ByteBuffer temp = ByteBuffer.allocate(newSize);
        buffer.flip();
        temp.put(buffer);
        buffer = temp;
        buffer.order(ByteOrder.BIG_ENDIAN);
      }
    }
  }

  /**
   * バイナリから各転送可能型にデシリアライズを行います。
   */
  private static class SimpleUnmarshal implements Unmarshal {
    private final ByteBuffer buffer;
    private SimpleUnmarshal(@Nonnull ByteBuffer buffer){
      buffer.order(ByteOrder.BIG_ENDIAN);
      this.buffer = buffer;
    }
    public byte readInt8() throws Unsatisfied {
      if(buffer.remaining() < Byte.BYTES){
        throw new Unsatisfied();
      }
      return buffer.get();
    }
    public short readInt16() throws Unsatisfied{
      if(buffer.remaining() < Short.BYTES){
        throw new Unsatisfied();
      }
      return buffer.getShort();
    }
    public int readInt32() throws Unsatisfied {
      if(buffer.remaining() < Integer.BYTES){
        throw new Unsatisfied();
      }
      return buffer.getInt();
    }
    public long readInt64() throws Unsatisfied {
      if(buffer.remaining() < Long.BYTES){
        throw new Unsatisfied();
      }
      return buffer.getLong();
    }
    public float readFloat32() throws Unsatisfied {
      if(buffer.remaining() < Float.BYTES){
        throw new Unsatisfied();
      }
      return buffer.getFloat();
    }
    public double readFloat64() throws Unsatisfied {
      if(buffer.remaining() < Double.BYTES){
        throw new Unsatisfied();
      }
      return buffer.getDouble();
    }
    public byte[] readBinary() throws Unsatisfied {
      int length = readUInt16();
      if(buffer.remaining() < length){
        throw new Unsatisfied();
      }
      byte[] b = new byte[length];
      buffer.get(b);
      return b;
    }
  }
}