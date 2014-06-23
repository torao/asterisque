/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.message;

import org.asterisque.Debug;
import org.asterisque.ProtocolViolationException;
import org.asterisque.codec.Codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

import static org.asterisque.Asterisque.Protocol.Version_0_1;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// StreamHeader
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * TCP のようなストリームをベースとした通信実装でストリームの先頭に出現しバージョン確認などをを行うためのバイ
 * ナリを表すクラスです。{@link org.asterisque.Wire} によって送受信されるものであり Session の
 * {@link org.asterisque.message.Message} とは別です。
 *
 * @author Takami Torao
 */
public final class StreamHeader {

	/**
	 * StreamHeader に必要な Control の data の長さ。
	 */
	public static final int DataLength = Short.BYTES + Long.BYTES * 2 + Long.BYTES * 2 + Long.BYTES + Integer.BYTES + Integer.BYTES;

	/**
	 * プロトコルのバージョンを表す 2 バイト整数値。上位バイトから [major][minor] の順を持つ。
	 * @see org.asterisque.Asterisque.Protocol#Signature
	 */
	public final short version;

	/**
	 * ノード ID。SSL を使用している場合はそちらの証明書と照合する必要がある。
	 */
	public final UUID nodeId;

	/**
	 * セッション ID 同期。接続後のクライアント Sync に対するサーバ応答でのみ有効な値を持つ。それ以外の場合は
	 * {@link org.asterisque.Asterisque#Zero} を送らなければならず、受け取った側は無視しなければならない。
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

	// ============================================================================================
	// コンストラクタ
	// ============================================================================================
	/**
	 *
	 */
	public StreamHeader(short version, UUID nodeId, UUID sessionId, long utcTime, int ping, int sessionTimeout) {
		this.version = version;
		this.nodeId = nodeId;
		this.sessionId = sessionId;
		this.utcTime = utcTime;
		this.ping = ping;
		this.sessionTimeout = sessionTimeout;
	}

	// ============================================================================================
	// コンストラクタ
	// ============================================================================================
	/**
	 *
	 */
	public StreamHeader(UUID nodeId, UUID sessionId, long utcTime, int ping, int sessionTimeout) {
		this.version = Version_0_1;
		this.nodeId = nodeId;
		this.sessionId = sessionId;
		this.utcTime = utcTime;
		this.ping = ping;
		this.sessionTimeout = sessionTimeout;
	}

	// ============================================================================================
	// ストリームヘッダの参照
	// ============================================================================================
	/**
	 * このインスタンスの内容でストリームヘッダのバイナリを生成します。
	 */
	public Control toControl(){
		// StreamHeader は Codec 実装に依存しない
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
		assert(buffer.capacity() == buffer.limit());
		return new Control(Control.StreamHeader, buffer.array());
	}

	// ==========================================================================================
	// ヘッダの参照
	// ==========================================================================================
	/**
	 * 指定されたバイトバッファからインスタンスを構築します。
	 * @throws org.asterisque.ProtocolViolationException asterisque のストリームではない場合
	 */
	public static StreamHeader parse(Control control) throws ProtocolViolationException{
		if(control.code != Control.StreamHeader){
			throw new ProtocolViolationException(
				String.format("invalid asterisque protocol: 0x%02X%02X", Codec.Msg.Control & 0xFF, control.code & 0xFF));
		}
		if(control.data.length < DataLength){
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
		return new StreamHeader(version, nodeId, sessionId, utcTime, ping, sessionTimeout);
	}

}
