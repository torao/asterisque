/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

import org.asterisque.codec.Codec;
import org.asterisque.netty.Netty;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// NetworkBridge
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

/**
 * TCP のようなソケットベースの接続や接続受け付けに使用するネットワーク実装です。
 *
 * @author Takami Torao
 */
public interface Bridge extends AutoCloseable {

	public static final Bridge DefaultBridge = new Netty();

	// ==============================================================================================
	// 接続の実行
	// ==============================================================================================
	/**
	 * 指定されたリモートノードに対して非同期接続を行い `Wire` の Future を返します。
	 * @param config 接続設定
	 * @return Wire の Future
	 */
	public CompletableFuture<Wire> newWire(Config config, LocalNode local, RemoteNode remote);

	// ==============================================================================================
	// 受付の実行
	// ==============================================================================================
	/**
	 * 指定されたネットワークからの接続を非同期で受け付ける `Server` の Future を返します。
	 * @param config 接続設定
	 * @return Server の Future
	 */
	public CompletableFuture<Server> newServer(Config config, LocalNode local, Network network, Consumer<Wire> onAccept);

	// ==============================================================================================
	// クローズ
	// ==============================================================================================
	/**
	 * このインスタンスをクローズしリソースを解放します。
	 */
	public void close();

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// Server
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * {@link #newServer(org.asterisque.Bridge.Config, LocalNode, Network, java.util.function.Consumer)}
	 * によって生成されるサーバをクローズするために使用するクラスです。
	 */
	public static abstract class Server implements Closeable {
		public final LocalNode local;
		public final Network network;
		public final Config config;
		protected Server(Config config, LocalNode local, Network network){
			this.local = local;
			this.network = network;
			this.config = config;
		}
		public abstract void close();
	}

	public static class Config {
		public final Optional<SSLContext> sslContext;
		public final Codec codec;
		// Server Options
		public final int backlog;
		// TODO 他に SocketOption など
		public Config(Optional<SSLContext> sslContext, Codec codec,
									int backlog){
			this.sslContext = sslContext;
			this.codec = codec;
			this.backlog = backlog;
		}
	}

}
