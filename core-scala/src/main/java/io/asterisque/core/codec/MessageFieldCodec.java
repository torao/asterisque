package io.asterisque.core.codec;

import io.asterisque.utils.Debug;
import io.asterisque.core.msg.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * メッセージのフィールドごとにバイナリに変換するコーデックです。
 * <p>
 * アプリケーションインターフェースで使用されているパラメータのシリアライズ/デシリアライズは Marshal と Unmarshal に
 * よってサブクラスの実装に委譲されています。
 *
 * @author Takami Torao
 */
public abstract class MessageFieldCodec implements MessageCodec {
  private static final Logger logger = LoggerFactory.getLogger(MessageFieldCodec.class);

  public interface Msg {
    byte Control = '*';
    byte Open = '(';
    byte Close = ')';
    byte Block = '#';
  }

  public interface Tag {
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
    byte Tuple = 34;
  }

  protected MessageFieldCodec() {
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
      serializeOpen(m, (Open) msg);
    } else if (msg instanceof Close) {
      m.writeTag(Msg.Close);
      serializeClose(m, (Close) msg);
    } else if (msg instanceof Block) {
      m.writeTag(Msg.Block);
      serializeBlock(m, (Block) msg);
    } else if (msg instanceof Control) {
      m.writeTag(Msg.Control);
      serializeControl(m, (Control) msg);
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
          return Optional.of(deserializeOpen(u));
        case Msg.Close:
          return Optional.of(deserializeClose(u));
        case Msg.Block:
          return Optional.of(deserializeBlock(u));
        case Msg.Control:
          return Optional.of(deserializeControl(u));
        default:
          throw new CodecException(String.format("unexpected message type: %02X", tag & 0xFF));
      }
    } catch (Unsatisfied ex) {
      buffer.position(pos);
      return Optional.empty();
    }
  }

  /**
   * フィールド値ごとの直列化処理を参照します。
   */
  @Nonnull
  public abstract Marshal newMarshal();

  /**
   * フィールド値ごとの非直列化処理を参照します。
   */
  @Nonnull
  public abstract Unmarshal newUnmarshal(@Nonnull ByteBuffer buffer);

  private void serializeOpen(@Nonnull Marshal m, @Nonnull Open open) {
    m.writeInt16(open.pipeId);
    m.writeInt8(open.priority);
    m.writeInt16(open.functionId);
    m.writeList(Arrays.asList(open.params));
  }

  @Nonnull
  private Open deserializeOpen(@Nonnull Unmarshal u) throws Unsatisfied {
    short pipeId = u.readInt16();
    byte priority = u.readInt8();
    short functionId = u.readInt16();
    List<?> params = u.readList();
    return new Open(pipeId, priority, functionId, params.toArray());
  }

  private void serializeClose(@Nonnull Marshal m, @Nonnull Close close) {
    m.writeInt16(close.pipeId);
    if (close.abort != null) {
      m.writeFalse();
      m.writeInt32(close.abort.code);
      m.writeString(close.abort.message);
    } else {
      m.writeTrue();
      m.write(close.result);
    }
  }

  @Nonnull
  private Close deserializeClose(@Nonnull Unmarshal u) throws Unsatisfied {
    short pipeId = u.readInt16();
    boolean success = u.readBoolean();
    if (success) {
      Object result = u.read();
      return new Close(pipeId, result);
    } else {
      int code = u.readInt32();
      String msg = u.readString();
      return new Close(pipeId, new Abort(code, msg));
    }
  }

  private void serializeBlock(@Nonnull Marshal m, @Nonnull Block block) {
    byte status = (byte) ((block.eof ? (1 << 7) : 0) | block.loss);
    assert (block.loss >= 0);
    m.writeInt16(block.pipeId);
    m.writeInt8(status);
    if (block.length > Block.MaxPayloadSize) {
      throw new CodecException(String.format("block payload length too large: %d / %d", block.length, Block.MaxPayloadSize));
    }
    m.writeBinary(block.payload, block.offset, block.length);
  }

  @Nonnull
  private Block deserializeBlock(@Nonnull Unmarshal u) throws Unsatisfied {
    short pipeId = u.readInt16();
    byte status = u.readInt8();
    boolean eof = ((1 << 7) & status) != 0;
    byte loss = (byte) (status & 0x7F);
    byte[] payload = u.readBinary();
    return new Block(pipeId, loss, payload, 0, payload.length, eof);
  }

  private void serializeControl(@Nonnull Marshal m, @Nonnull Control control) {
    m.writeInt8(control.code);
    m.writeBinary(control.data);
  }

  @Nonnull
  private Control deserializeControl(@Nonnull Unmarshal u) throws Unsatisfied {
    byte code = u.readInt8();
    byte[] data = u.readBinary();
    return new Control(code, data);
  }
}
