package io.asterisque.core.msg;

import io.asterisque.Asterisque;
import io.asterisque.ProtocolViolationException;
import io.asterisque.utils.Debug;
import io.asterisque.wire.ProtocolException;
import io.asterisque.core.codec.MessageFieldCodec;

import javax.annotation.Nonnull;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.asterisque.Asterisque.Protocol.CurrentVersion;

/**
 * 通信の開始時にピア間の設定を同期するための制御メッセージに付随するバイナリフィールドを参照するためのヘルパークラスです。
 * {@link Control#SyncSession} の制御コードを持つ Control メッセージのデータに使用できます。
 *
 * @author Takami Torao
 */
public final class SyncSession {

  /**
   * SyncSession 制御メッセージに必要な Control の data の長さの最小値。
   */
  public static final int MinLength = Short.BYTES + Long.BYTES * 2 + Long.BYTES * 2 + Byte.BYTES + Long.BYTES + Integer.BYTES + Integer.BYTES;

  /**
   * プロトコルのバージョンを表す 2 バイト整数値。上位バイトから [major][minor] の順を持つ。
   *
   * @see Asterisque.Protocol#Signature
   */
  public final short version;

  /**
   * ノード ID。通信が TLS を使用している場合はそちらの証明書と照合する必要がある。
   */
  public final UUID nodeId;

  /**
   * セッション ID 同期。接続後のクライアント Sync に対するサーバ応答でのみ有効な値を持つ。それ以外の場合は
   * {@link Asterisque#Zero} を送らなければならず、受け取った側は無視しなければならない。
   */
  public final UUID sessionId;

  /**
   *
   */
  public final String serviceId;

  /**
   * UTC ミリ秒で表現した現在時刻。システム時刻確認の目的で使用される。
   */
  public final long utcTime;

  /**
   * サーバからクライアントへセッションの死活監視を行うための ping 間隔 (秒)。
   */
  public final int ping;

  /**
   * セッションタイムアウトまでの間隔 (秒)。
   */
  public final int sessionTimeout;

  /**
   * 全てのプロパティを指定して構築を行います。
   */
  public SyncSession(short version, @Nonnull UUID nodeId, @Nonnull UUID sessionId, @Nonnull String serviceId,
                     long utcTime, int ping, int sessionTimeout) {
    if (serviceId.getBytes(StandardCharsets.UTF_8).length > 0xFF) {
      throw new IllegalArgumentException("service id too long: " + serviceId);
    }
    this.version = version;
    this.nodeId = nodeId;
    this.sessionId = sessionId;
    this.serviceId = serviceId;
    this.utcTime = utcTime;
    // TODO ping, sessionTimeout の入力値チェックとテストケース作成
    this.ping = ping;
    this.sessionTimeout = sessionTimeout;
  }

  /**
   * 現在のバージョンを使用して構築します。
   */
  public SyncSession(@Nonnull UUID nodeId, @Nonnull UUID sessionId, @Nonnull String serviceId,
                     long utcTime, int ping, int sessionTimeout) {
    this(CurrentVersion, nodeId, sessionId, serviceId, utcTime, ping, sessionTimeout);
  }

  /**
   * このインスタンスの内容から SyncSession バイナリのデータを持った Control メッセージを作成します。
   *
   * @return Control メッセージ
   */
  @Nonnull
  public Control toControl() {
    // SyncSession は MessageCodec 実装に依存せず BIGENDIAN でエンコードする
    byte[] serviceId = this.serviceId.getBytes(StandardCharsets.UTF_8);
    ByteBuffer buffer = ByteBuffer.allocate(MinLength + serviceId.length);
    buffer.order(ByteOrder.BIG_ENDIAN)
        .putShort(version)
        .putLong(nodeId.getMostSignificantBits())
        .putLong(nodeId.getLeastSignificantBits())
        .putLong(sessionId.getMostSignificantBits())
        .putLong(sessionId.getLeastSignificantBits())
        .put((byte) serviceId.length)
        .put(serviceId)
        .putLong(utcTime)
        .putInt(ping)
        .putInt(sessionTimeout);
    assert (buffer.capacity() == buffer.limit());
    return new Control(Control.SyncSession, buffer.array());
  }

  /**
   * 指定されたバイトバッファからインスタンスを構築します。
   *
   * @throws ProtocolViolationException asterisque のストリームではない場合
   */
  @Nonnull
  public static SyncSession parse(@Nonnull Control control) throws ProtocolViolationException {
    if (control.code != Control.SyncSession) {
      throw new ProtocolViolationException(
          String.format("invalid asterisque protocol: 0x%02X%02X", MessageFieldCodec.Msg.Control & 0xFF, control.code & 0xFF));
    }
    if (control.data.length < MinLength) {
      throw new ProtocolViolationException(
          String.format("data-length too short: %d < %d: %s", control.data.length, MinLength, Debug.toString(control.data)));
    }
    ByteBuffer buffer = ByteBuffer.wrap(control.data);
    buffer.order(ByteOrder.BIG_ENDIAN);
    short version = buffer.getShort();
    UUID nodeId = new UUID(buffer.getLong(), buffer.getLong());
    UUID sessionId = new UUID(buffer.getLong(), buffer.getLong());
    int serviceIdLength = buffer.get() & 0xFF;
    byte[] serviceIdBinary = new byte[serviceIdLength];
    buffer.get(serviceIdBinary);
    String serviceId = new String(serviceIdBinary, StandardCharsets.UTF_8);
    long utcTime = buffer.getLong();
    int ping = buffer.getInt();
    int sessionTimeout = buffer.getInt();
    return new SyncSession(version, nodeId, sessionId, serviceId, utcTime, ping, sessionTimeout);
  }

  public static class Pair {
    public final SyncSession primary;
    public final SyncSession secondary;

    public Pair(@Nonnull SyncSession primary, @Nonnull SyncSession secondary) {
      this.primary = primary;
      this.secondary = secondary;
    }

    public UUID sessionId(){
      return primary.sessionId;
    }

    public int ping(){
      return Math.min(primary.ping, secondary.ping);
    }

    public void verify() throws ProtocolException  {

    }
  }

}
