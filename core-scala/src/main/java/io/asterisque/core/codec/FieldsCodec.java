package io.asterisque.core.codec;

import io.asterisque.Debug;
import io.asterisque.core.msg.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;

/**
 * アプリケーションインターフェースで使用されているパラメータのシリアライズ/デシリアライズは Marshal と Unmarshal に
 * よってサブクラスの実装に委譲されています。
 *
 * @author Takami Torao
 */
public abstract class StandardCodec  implements Codec {
  private static final Logger logger = LoggerFactory.getLogger(StandardCodec.class);

  interface Msg {
    byte Control = '*';
    byte Open = '(';
    byte Close = ')';
    byte Block = '#';
  }

  interface Tag {
    byte Null = 0;
    byte True = 1;
    byte False = 2;
    byte Int8 = 3;
    byte Int16 = 4;
    byte Int32 = 5;
    byte Int64 = 6;
    byte Float32 = 7;
    byte Float64 = 8;
    byte Binary = 10;
    byte String = 11;
    byte UUID = 12;

    byte List = 32;
    byte Map = 33;
    byte Struct = 34;
  }

  /**
   * 指定されたメッセージをサブクラスが実装する形式でバイナリ形式にシリアライズします。変換後のバイナリサイズが
   * {@link #MaxMessageSize} を超える場合には {@link CodecException} が発生します。
   *
   * @param msg エンコードするメッセージ
   * @return エンコードされたバイトバッファ
   * @throws CodecException シリアライズに失敗した場合
   */
  @Nonnull
  public ByteBuffer encode(@Nonnull Message msg) throws CodecException {
    Objects.requireNonNull(msg);
    if (logger.isTraceEnabled()) {
      logger.trace("encode(" + msg + ")");
    }
    Marshal m = newMarshal();
    if (msg instanceof Open) {
      m.writeTag(Msg.Open);
      Transformer.Open.serialize(m, (Open) msg);
    } else if (msg instanceof Close) {
      m.writeTag(Msg.Close);
      Transformer.Close.serialize(m, (Close) msg);
    } else if (msg instanceof Block) {
      m.writeTag(Msg.Block);
      Transformer.Block.serialize(m, (Block) msg);
    } else if (msg instanceof Control) {
      m.writeTag(Msg.Control);
      Transformer.Control.serialize(m, (Control) msg);
    } else {
      throw new IllegalStateException(String.format("unexpected message type: %s", msg.getClass().getName()));
    }
    ByteBuffer b = m.toByteBuffer();
    assert (b.remaining() > 0);
    if (b.remaining() > MaxMessageSize) {
      throw new CodecException(String.format("message binary too large: %d > %d: %s", b.remaining(), MaxMessageSize, msg));
    }
    return b;
  }

  /**
   * 指定されたバイナリ表現のメッセージをデコードします。
   * <p>
   * このメソッドの呼び出しはデータを受信する都度行われます。従って、サブクラスはメッセージ全体を復元できるだけデータを受信して
   * いない場合に {@code empty()} を返す必要があります。
   * <p>
   * パラメータの {@link ByteBuffer} の位置は次回の呼び出しまで維持されます。このためサブクラスは復元したメッセージの
   * 次の適切な読み出し位置を正しくポイントする必要があります。またメッセージを復元できるだけのデータを受信していない場合には
   * 読み出し位置を変更すべきではありません。コーデック実装により無視できるバイナリが存在する場合はバッファ位置を変更して
   * {@code empty()} を返す事が出来ます。
   * <p>
   * メッセージのデコードに失敗した場合は [[com.kazzla.asterisk.codec.CodecException]] が発生します。
   *
   * @param buffer デコードするメッセージ
   * @return デコードしたメッセージ
   * @throws CodecException デコードに失敗した場合
   */
  @Nonnull
  public Optional<Message> decode(@Nonnull ByteBuffer buffer) {
    if (logger.isTraceEnabled()) {
      logger.trace("decode(" + Debug.toString(buffer) + ")");
    }
    int pos = buffer.position();
    try {
      Unmarshal u = newUnmarshal(buffer);
      byte tag = u.readTag();
      switch (tag) {
        case Msg.Open:
          return Optional.of(Transformer.Open.deserialize(u));
        case Msg.Close:
          return Optional.of(Transformer.Close.deserialize(u));
        case Msg.Block:
          return Optional.of(Transformer.Block.deserialize(u));
        case Msg.Control:
          return Optional.of(Transformer.Control.deserialize(u));
        default:
          throw new CodecException(String.format("unexpected message type: %02X", tag & 0xFF));
      }
    } catch (Unsatisfied ex) {
      buffer.position(pos);
      return Optional.empty();
    }
  }

  /**
   * 直列化処理を参照します。
   */
  @Nonnull
  public abstract Marshal newMarshal();

  /**
   * 非直列化処理を参照します。
   */
  @Nonnull
  public abstract Unmarshal newUnmarshal(ByteBuffer buffer);

  /**
   * 読み込み済みのバイナリがメッセージを復元するために不十分である場合に {@link Unmarshal} の読み出し処理で発生します。
   * この例外が発生した場合、データを復元するためにさらなるバイナリを読み出してデータ復元を再実行する必要があることを
   * {@code Codec} に通知します。
   */
  public static class Unsatisfied extends Exception {
  }

}
