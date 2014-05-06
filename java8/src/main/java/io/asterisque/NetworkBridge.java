/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque;

import io.asterisque.codec.Codec;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.net.SocketAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// NetworkBridge
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * IP ベースの接続や接続受け付けに使用するネットワーク実装です。
 *
 * @author Takami Torao
 */
public interface NetworkBridge {

	// ==============================================================================================
	// 接続の実行
	// ==============================================================================================
	/**
	 * 指定されたアドレスに対して非同期接続を行い `Wire` の Future を返します。
	 * @param codec Wire 上で使用するコーデック
	 * @param address 接続先のアドレス
	 * @param sslContext クライアント認証を行うための SSL 証明書 (Noneの場合は非SSL接続)
	 * @return Wire の Future
	 */
	public CompletableFuture<Wire> connect(Codec codec, SocketAddress address, Optional<SSLContext> sslContext);

	// ==============================================================================================
	// 受付の実行
	// ==============================================================================================
	/**
	 * 指定されたアドレスに対して非同期で接続を受け付ける `Server` の Future を返します。
	 * @param codec Wire 上で使用するコーデック
	 * @param address バインド先のアドレス
	 * @param sslContext サーバ認証を行うための SSL 証明書 (Noneの場合は非SSL接続)
	 * @param onAccept サーバ上で新しい接続が発生した時のコールバック
	 * @return Server の Future
	 */
	public CompletableFuture<Server> listen(
		Codec codec, SocketAddress address, Optional<SSLContext> sslContext, Consumer<Wire> onAccept);

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// Server
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * {@link io.asterisque.NetworkBridge#listen(io.asterisque.codec.Codec, java.net.SocketAddress,
	 * java.util.Optional, java.util.function.Consumer)} によって生成されるサーバをクローズするために使用す
	 * るクラスです。
	 */
	public static class Server implements Closeable {
		public final SocketAddress address;
		public Server(SocketAddress address){
			this.address = address;
		}
		public void close(){ }
	}

	public static class Config {
		public final Codec codec;
		public final SocketAddress address;
		public final Optional<SSLContext> sslContext;
		public Config(Codec codec, SocketAddress address, Optional<SSLContext> sslContext){
			this.codec = codec;
			this.address = address;
			this.sslContext = sslContext;
		}
	}

}
