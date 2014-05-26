/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque;

import io.asterisque.conf.ConnectConfig;
import io.asterisque.conf.ListenConfig;
import io.asterisque.netty.Netty;

import java.io.Closeable;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// NetworkBridge
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * TCP のようなソケットベースの接続や接続受け付けに使用するネットワーク実装です。
 *
 * @author Takami Torao
 */
public interface NetworkBridge {

	public static final NetworkBridge DefaultBridge = new Netty();

	// ==============================================================================================
	// 接続の実行
	// ==============================================================================================
	/**
	 * 指定されたアドレスに対して非同期接続を行い `Wire` の Future を返します。
	 * @param config 接続設定
	 * @return Wire の Future
	 */
	public CompletableFuture<Wire> connect(ConnectConfig config, Executor executor, Consumer<Message> dispatcher, Consumer<Wire> disposer);

	// ==============================================================================================
	// 受付の実行
	// ==============================================================================================
	/**
	 * 指定されたアドレスに対して非同期で接続を受け付ける `Server` の Future を返します。
	 * @param config 接続設定
	 * @return Server の Future
	 */
	public CompletableFuture<Server> listen(ListenConfig config, Executor executor, Consumer<Wire> onAccept);

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// Server
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * {@link #listen(io.asterisque.conf.ListenConfig, java.util.concurrent.Executor, java.util.function.Consumer)}
	 * によって生成されるサーバをクローズするために使用するクラスです。
	 */
	public static class Server implements Closeable {
		public final SocketAddress address;
		public Server(SocketAddress address){
			this.address = address;
		}
		public void close(){ }
	}

}
