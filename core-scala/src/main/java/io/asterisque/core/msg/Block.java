package io.asterisque.core.msg;

import io.asterisque.Asterisque;
import io.asterisque.Pipe;

import javax.annotation.Nonnull;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Objects;

/**
 * {@link Pipe} を経由して双方向で交換可能なメッセージです。パイプ間での双方向ストリーミングのために使用されます。
 *
 * @author Takami Torao
 */
public final class Block extends Message {

  /**
   * ペイロードに設定できる最大サイズです。0xEFFF (61,439バイト) を表します。
   */
  public static int MaxPayloadSize = 0xFFFF - (4 * 1024);

  /**
   * 損失率はこのブロックが過負荷などによって消失しても良い確率を表す 0〜127 までの値です。0 はこのブロックが消失しないことを
   * 表し、127 は 100% の消失が発生しても良い事を示します。EOF を示す場合でも 0 以外の値をとることことができます。
   */
  public final byte loss;

  /**
   * このブロックが転送するデータを保持しているバッファです。有効なデータはバイト配列の長さより小さい可能性があります。
   */
  @Nonnull
  public final byte[] payload;

  /**
   * バッファ内で有効なデータの開始位置を表すオフセットです。
   */
  public final int offset;

  /**
   * バッファ内で有効なデータの長さを表す値です。
   */
  public final int length;

  /**
   * このブロックが EOF を表すかを示すフラグです。
   */
  public final boolean eof;

  /**
   * Block メッセージを構築します。{@link #MaxPayloadSize} より大きいペイロードを指定すると例外が発生します。
   *
   * @param pipeId  宛先のパイプ ID
   * @param loss    このブロックの損失率
   * @param payload このブロックのペイロード
   * @param offset  `payload` 内のデータ開始位置
   * @param length  `payload` 内のデータの大きさ
   * @param eof     このブロックがストリームの終端を表す場合 true
   * @throws IllegalArgumentException パラメータの一つが不正な場合
   */
  public Block(short pipeId, byte loss, @Nonnull byte[] payload, int offset, int length, boolean eof) {
    super(pipeId);
    Objects.requireNonNull(payload, "payload shouldn't be null");
    if (offset + length > payload.length) {
      throw new IllegalArgumentException("buffer overrun: offset=" + offset + ", length=" + length + ", actual=" + payload.length);
    }
    if (offset < 0 || length < 0) {
      throw new IllegalArgumentException("negative value: offset=" + offset + ", length=" + length);
    }
    if (length > MaxPayloadSize) {
      throw new IllegalArgumentException("too long payload: " + length + ", max=" + MaxPayloadSize);
    }
    if (loss < 0) {
      throw new IllegalArgumentException("invalid loss-rate: " + loss);
    }
    this.loss = loss;
    this.payload = payload;
    this.offset = offset;
    this.length = length;
    this.eof = eof;
  }

  /**
   * 損失率付きの Block メッセージを構築します。
   *
   * @param pipeId  宛先のパイプ ID
   * @param loss    このブロックの損失率 (0-127)
   * @param payload このブロックのペイロード
   * @param offset  `payload` 内のデータ開始位置
   * @param length  `payload` 内のデータの大きさ
   * @throws IllegalArgumentException パラメータの一つが不正な場合
   */
  public Block(short pipeId, byte loss, @Nonnull byte[] payload, int offset, int length) {
    this(pipeId, loss, payload, offset, length, false);
  }

  /**
   * 通常の Block メッセージを構築します。損失率は 0 に設定されます。
   *
   * @param pipeId  宛先のパイプ ID
   * @param payload このブロックのペイロード
   * @param offset  `payload` 内のデータ開始位置
   * @param length  `payload` 内のデータの大きさ
   * @throws IllegalArgumentException パラメータの一つが不正な場合
   */
  public Block(short pipeId, @Nonnull byte[] payload, int offset, int length) {
    this(pipeId, (byte) 0, payload, offset, length, false);
  }

  /**
   * このブロックのペイロードを ByteBuffer として参照します。オフセットの指定により初期状態のポジションが 0 でない可能性が
   * あります。
   *
   * @return ペイロードの ByteBuffer
   */
  @Nonnull
  public ByteBuffer toByteBuffer() {
    return ByteBuffer.wrap(payload, offset, length);
  }

  /**
   * このブロックのペイロードを UTF-8 でデコードした文字列として参照します。
   *
   * @return ペイロードを UTF-8 でデコードした文字列
   */
  @Nonnull
  public String getString() {
    return getString(Asterisque.UTF8);
  }

  /**
   * このブロックのペイロードを指定された文字セットでエンコードされた文字列として参照します。
   *
   * @param charset ペイロードの文字セット
   * @return ペイロードの文字列表現
   */
  @Nonnull
  public String getString(@Nonnull Charset charset) {
    return new String(payload, offset, length, charset);
  }

  /**
   * このインスタンスを文字列化します。
   */
  @Override
  @Nonnull
  public String toString() {
    StringBuilder buffer = new StringBuilder(length * 3);
    for (int i = 0; i < length; i++) {
      if (i != 0) {
        buffer.append(",");
      }
      buffer.append(String.format("%02X", payload[offset + i]));
    }
    return String.format("Block(%d,[%s],%d%s)", pipeId, buffer, loss, eof ? ",EOF" : "");
  }

  @Override
  public int hashCode() {
    int hash = 0;
    for(int i=0; i<Integer.BYTES; i++){
      hash <<= 8;
      if(i < length){
        hash |= payload[offset + i] & 0xFF;
      }
    }
    return hash ^ length;
  }

  @Override
  public boolean equals(Object obj) {
    if (super.equals(obj) && obj instanceof Block) {
      Block other = (Block) obj;
      if (this.length != other.length || this.eof != other.eof || this.loss != other.loss) {
        return false;
      }
      for (int i = 0; i < this.length; i++) {
        if (this.payload[this.offset + i] != other.payload[other.offset + i]) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  /**
   * 指定されたパイプ ID に対する EOF ブロックを構築します。
   *
   * @param pipeId 宛先のパイプ ID
   * @return EOF ブロック
   */
  @Nonnull
  public static Block eof(short pipeId) {
    return new Block(pipeId, (byte) 0, Asterisque.Empty.Bytes, 0, 0, true);
  }

}
