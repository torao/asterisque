package io.asterisque.msg;

import io.asterisque.Asterisque;
import io.asterisque.Debug;
import io.asterisque.ProtocolViolationException;
import io.asterisque.codec.Codec;

import javax.annotation.Nonnull;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

import static io.asterisque.Asterisque.Protocol.CurrentVersion;

/**
 * 通信の開始時にピア間の設定を同期するための制御メッセージに付随するバイナリフィールドを参照するためのヘルパークラスです。
 * {@link Control#SyncConfig} の制御コードを持つ Control メッセージのデータに使用できます。
 *
 * @author Takami Torao
 */
public final class SyncConfig {

  /**
   * SyncConfig 制御メッセージに必要な Control の data の長さ。
   */
  public static final int DataLength = Short.BYTES + Long.BYTES * 2 + Long.BYTES * 2 + Long.BYTES + Integer.BYTES + Integer.BYTES;

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
   * UTC ミリ秒で表現した現在時刻。
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
  public SyncConfig(short version, UUID nodeId, UUID sessionId, long utcTime, int ping, int sessionTimeout) {
    this.version = version;
    this.nodeId = nodeId;
    this.sessionId = sessionId;
    this.utcTime = utcTime;
    // TODO ping, sessionTimeout の入力値チェックとテストケース作成
    this.ping = ping;
    this.sessionTimeout = sessionTimeout;
  }

  /**
   * 現在のバージョンを使用して構築します。
   */
  public SyncConfig(UUID nodeId, UUID sessionId, long utcTime, int ping, int sessionTimeout) {
    this(CurrentVersion, nodeId, sessionId, utcTime, ping, sessionTimeout);
  }

  /**
   * このインスタンスの内容から SyncConfig バイナリのデータを持った Control メッセージを作成します。
   *
   * @return Control メッセージ
   */
  @Nonnull
  public Control toControl() {
    // SyncConfig は Codec 実装に依存しない
    ByteBuffer buffer = ByteBuffer.allocate(DataLength);
    buffer.order(ByteOrder.BIG_ENDIAN)
        .putShort(version)
        .putLong(nodeId.getMostSignificantBits())
        .putLong(nodeId.getLeastSignificantBits())
        .putLong(sessionId.getMostSignificantBits())
        .putLong(sessionId.getLeastSignificantBits())
        .putLong(utcTime)
        .putInt(ping)
        .putInt(sessionTimeout);
    assert (buffer.capacity() == buffer.limit());
    return new Control(Control.SyncConfig, buffer.array());
  }

  /**
   * 指定されたバイトバッファからインスタンスを構築します。
   *
   * @throws ProtocolViolationException asterisque のストリームではない場合
   */
  @Nonnull
  public static SyncConfig parse(@Nonnull Control control) throws ProtocolViolationException {
    if (control.code != Control.SyncConfig) {
      throw new ProtocolViolationException(
          String.format("invalid asterisque protocol: 0x%02X%02X", Codec.Msg.Control & 0xFF, control.code & 0xFF));
    }
    if (control.data.length < DataLength) {
      throw new ProtocolViolationException(
          String.format("data-length too short: %d < %d: %s", control.data.length, DataLength, Debug.toString(control.data)));
    }
    ByteBuffer buffer = ByteBuffer.wrap(control.data);
    buffer.order(ByteOrder.BIG_ENDIAN);
    short version = buffer.getShort();
    UUID nodeId = new UUID(buffer.getLong(), buffer.getLong());
    UUID sessionId = new UUID(buffer.getLong(), buffer.getLong());
    long utcTime = buffer.getLong();
    int ping = buffer.getInt();
    int sessionTimeout = buffer.getInt();
    return new SyncConfig(version, nodeId, sessionId, utcTime, ping, sessionTimeout);
  }

}
