/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

import org.asterisque.netty.Netty;

import java.io.Closeable;
import java.net.SocketAddress;
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
	 * @param options 接続設定
	 * @return Wire の Future
	 */
	public CompletableFuture<Wire> newWire(LocalNode local, SocketAddress address, Options options);

	// ==============================================================================================
	// 受付の実行
	// ==============================================================================================
	/**
	 * 指定されたネットワークからの接続を非同期で受け付ける `Server` の Future を返します。
	 * @param options 接続設定
	 * @return Server の Future
	 */
	public CompletableFuture<Server> newServer(LocalNode local, SocketAddress address, Options options, Consumer<Wire> onAccept);

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
	 * {@link #newServer(LocalNode, java.net.SocketAddress, Options, java.util.function.Consumer)}
	 * によって生成されるサーバをクローズするために使用するクラスです。
	 */
	public static abstract class Server implements Closeable {
		public final LocalNode node;
		public final SocketAddress address;
		public final Options options;
		protected Server(LocalNode node, SocketAddress address, Options options){
			this.node = node;
			this.address = address;
			this.options = options;
		}
		public abstract void close();
	}

}
