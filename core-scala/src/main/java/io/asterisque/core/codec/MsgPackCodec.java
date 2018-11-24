package io.asterisque.core.codec;

import org.msgpack.MessagePack;
import org.msgpack.packer.BufferPacker;
import org.msgpack.unpacker.BufferUnpacker;

import javax.annotation.Nonnull;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * メッセージフィールドを MessagePack でエンコードするコーデックです。
 * インスタンスはスレッドセーフであり {@link MessageCodec#MessagePackCodec} で使用することができます。
 *
 * @author Takami Torao
 */
public class MsgPackCodec extends MessageFieldCodec {

  /**
   * インスタンスは MessageCodec 上の Singleton として構築されます。
   */
  MsgPackCodec() {
  }

  /**
   * 直列化処理を参照します。
   */
  @Nonnull
  public Marshal newMarshal() {
    return new MsgPackMarshal();
  }

  /**
   * 非直列化処理を参照します。
   */
  @Nonnull
  public Unmarshal newUnmarshal(@Nonnull ByteBuffer buffer) {
    return new MsgPackUnmarshal(buffer);
  }

  private static class MsgPackMarshal implements Marshal {
    private final MessagePack msgpack = new MessagePack();
    private final BufferPacker packer = msgpack.createBufferPacker();

    private MsgPackMarshal() {
    }

    @Nonnull
    public ByteBuffer toByteBuffer() {
      return ByteBuffer.wrap(packer.toByteArray());
    }

    public void writeInt8(byte i) {
      try {
        packer.write(i);
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    public void writeInt16(short i) {
      try {
        packer.write(i);
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    public void writeInt32(int i) {
      try {
        packer.write(i);
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    public void writeInt64(long i) {
      try {
        packer.write(i);
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    public void writeFloat32(float i) {
      try {
        packer.write(i);
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    public void writeFloat64(double i) {
      try {
        packer.write(i);
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    public void writeBinary(@Nonnull byte[] b, int offset, int length) {
      try {
        packer.write(b, offset, length);
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }
  }

  private static class MsgPackUnmarshal implements Unmarshal {
    private final BufferUnpacker unpacker;

    private MsgPackUnmarshal(@Nonnull ByteBuffer buffer) {
      this.unpacker = new MessagePack().createBufferUnpacker(buffer);
    }

    public byte readInt8() throws Unsatisfied {
      try {
        return unpacker.readByte();
      } catch (EOFException ex) {
        throw new Unsatisfied();
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    public short readInt16() throws Unsatisfied {
      try {
        return unpacker.readShort();
      } catch (EOFException ex) {
        throw new Unsatisfied();
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    public int readInt32() throws Unsatisfied {
      try {
        return unpacker.readInt();
      } catch (EOFException ex) {
        throw new Unsatisfied();
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    public long readInt64() throws Unsatisfied {
      try {
        return unpacker.readLong();
      } catch (EOFException ex) {
        throw new Unsatisfied();
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    public float readFloat32() throws Unsatisfied {
      try {
        return unpacker.readFloat();
      } catch (EOFException ex) {
        throw new Unsatisfied();
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    public double readFloat64() throws Unsatisfied {
      try {
        return unpacker.readDouble();
      } catch (EOFException ex) {
        throw new Unsatisfied();
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }

    @Nonnull
    public byte[] readBinary() throws Unsatisfied {
      try {
        return unpacker.readByteArray();
      } catch (EOFException ex) {
        throw new Unsatisfied();
      } catch (IOException ex) {
        throw new IllegalStateException(ex);
      }
    }
  }

}