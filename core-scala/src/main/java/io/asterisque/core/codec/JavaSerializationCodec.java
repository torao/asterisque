package io.asterisque.core.codec;

import io.asterisque.utils.Debug;
import io.asterisque.core.msg.Message;

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * Message codec implementation using Java object serialization. A Message will encode to 2 byte binary length and
 * serialized binary.
 *
 * @author Takami Torao
 */
public class JavaSerializationCodec implements MessageCodec {

  /**
   * バイナリのサイズを格納するための UINT16 分のパティング領域。
   */
  private static final byte[] UINT16_PADDING = new byte[Short.BYTES];

  /**
   * デシリアライズ時に使用するクラスローダー。
   */
  private final ClassLoader loader;

  /**
   * デシリアライズ時に使用するクラスローダーを指定して Java シリアライズ機能を使用したコーデック構築を行います。
   *
   * @param loader クラスローダー
   */
  public JavaSerializationCodec(@Nonnull ClassLoader loader) {
    this.loader = loader;
  }

  /**
   * このコンストラクタを呼び出したスレッドのコンテキストクラスローダーを使用する Java シリアライズ機能を使用したコーデックを
   * 構築します。
   */
  public JavaSerializationCodec() {
    this(Thread.currentThread().getContextClassLoader());
  }

  /**
   * Java シリアライゼーションを使用してメッセージをエンコードします。メッセージは 2 バイトの長さとシリアライズされた
   * バイナリに変換されます。
   *
   * @param msg エンコードするメッセージ
   * @return エンコードされたメッセージ
   */
  @Nonnull
  public ByteBuffer encode(@Nonnull Message msg) throws CodecException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try {
      baos.write(UINT16_PADDING);    // 後で長さを格納するためパディング
      ObjectOutputStream out = new ObjectOutputStream(baos);
      out.writeObject(msg);
      out.flush();
      out.close();
    } catch (Exception ex) {
      throw new CodecException("message cannot serialize: " + msg, ex);
    }
    ByteBuffer buffer = ByteBuffer.wrap(baos.toByteArray());
    if (buffer.remaining() > MaxMessageSize) {
      throw new CodecException(
          String.format("serialized size too long: %,d bytes > %,d bytes", buffer.remaining(), MaxMessageSize));
    }
    buffer.order(ByteOrder.BIG_ENDIAN);
    buffer.position(0);
    buffer.putShort((short) (buffer.remaining() - UINT16_PADDING.length));
    buffer.position(0);
    return buffer;
  }

  /**
   * Java シリアライゼーションを使用してメッセージをデコードします。
   *
   * @param buffer デコードするメッセージ
   * @return デコードしたメッセージ
   */
  @Nonnull
  public Optional<Message> decode(@Nonnull ByteBuffer buffer) throws CodecException {
    buffer.order(ByteOrder.BIG_ENDIAN);
    if (buffer.remaining() < Short.BYTES) {
      return Optional.empty();
    }

    int length = buffer.getShort() & 0xFFFF;
    if (buffer.remaining() < length) {
      buffer.position(buffer.position() - Short.BYTES);
      return Optional.empty();
    }

    byte[] buf = new byte[length];
    buffer.get(buf);

    ByteArrayInputStream bais = new ByteArrayInputStream(buf);
    try {
      ObjectInputStream in = new ObjectInputStream(bais) {
        protected Class<?> resolveClass(ObjectStreamClass desc) throws ClassNotFoundException, IOException {
          String name = desc.getName();
          try {
            return Class.forName(name, false, loader);
          } catch (ClassNotFoundException ex) {
            return super.resolveClass(desc);
          }
        }
      };
      return Optional.of((Message) in.readObject());
    } catch (InvalidClassException ex) {
      throw new CodecException(ex.classname, ex);
    } catch (Exception ex) {
      throw new CodecException("binary is not a serialized message: " + Debug.toString(buf), ex);
    }
  }

}