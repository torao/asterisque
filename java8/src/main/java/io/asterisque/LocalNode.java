/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque;

import io.asterisque.codec.Codec;
import io.asterisque.codec.MessagePackCodec;
import io.asterisque.netty.Netty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// LocalNode
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
public class LocalNode {
	private static final Logger logger = LoggerFactory.getLogger(LocalNode.class);

	/**
	 * このノード上で新しいセッションが発生した時に初期状態で使用するサービス。
	 */
	private volatile Service service;

	/**
	 * このノード上で Listen しているすべてのサーバ。ノードのシャットダウン時にクローズされる。
	 */
	private final AtomicReference<Collection<NetworkBridge.Server>> servers = new AtomicReference<>(Collections.emptyList());

	/**
	 * このノード上で使用されているすべてのセッション。ノードのシャットダウン時にクローズされる。
	 */
	private final AtomicReference<Collection<Session>> sessions = new AtomicReference<>(Collections.emptyList());

	public final String name;
	private final NetworkBridge bridge;
	private final Codec codec;
	public LocalNode(String name, Service initService, NetworkBridge bridge, Codec codec){
		this.name = name;
		this.service = initService;
		this.bridge = bridge;
		this.codec = codec;
	}

	/**
	 * このノード上で新しいセッションが発生した時のデフォルトのサービスを変更します。
	 * @param newService 新しいサービス
	 * @return 現在設定されているサービス
	 */
	public Service setService(Service newService) {
		Service old = service;
		service = newService;
		return old;
	}

	/**
	 * このノード上で有効なすべてのセッションを参照します。
	 */
	public Iterable<Session> getSessions() {
		return sessions.get();
	}

	// ==============================================================================================
	// 接続受け付けの開始
	// ==============================================================================================
	/**
	 * このノード上でリモートのノードからの接続を受け付けを開始します。アプリケーションは返値の [[Bridge.Server]]
	 * を使用してこの呼び出しで開始した接続受け付けを終了することが出来ます。
	 *
	 * アプリケーションは `onAccept` に指定した処理で新しい接続を受け付けセッションが発生した時の挙動を実装すること
	 * が出来ます。
	 *
	 * @param address バインドアドレス
	 * @param tls 通信に使用する SSLContext
	 * @param onAccept 新規接続を受け付けた時に実行する処理
	 * @return Server の Future
	 */
	public CompletableFuture<NetworkBridge.Server> listen(SocketAddress address, Optional<SSLContext> tls, Consumer<Session> onAccept) {
		CompletableFuture<NetworkBridge.Server> future = new CompletableFuture<>();
		bridge.listen(codec, address, tls, wire -> onAccept.accept(bind(wire))).whenComplete((server, ex) -> {
			if(server != null) {
				add(servers, server);
				future.complete(new NetworkBridge.Server(server.address) {
					@Override
					public void close() {
						remove(servers, server);
						server.close();
					}
				});
			} else {
				future.completeExceptionally(ex);
			}
		});
		return future;
	}
	public CompletableFuture<NetworkBridge.Server> listen(SocketAddress address, Optional<SSLContext> tls) {
		return listen(address, tls, s -> { });
	}
	public CompletableFuture<NetworkBridge.Server> listen(SocketAddress address) {
		return listen(address, Optional.empty());
	}

	// ==============================================================================================
	// ノードへの接続
	// ==============================================================================================
	/**
	 * このノードから指定されたアドレスの別のノードへ接続を行います。
	 *
	 * @param address 接続するノードのアドレス
	 * @param tls 通信に使用する SSLContext
	 * @return 接続により発生した Session の Future
	 */
	public CompletableFuture<Session> connect(SocketAddress address, Optional<SSLContext> tls) {
		return bridge.connect(codec, address, tls).thenApply(this::bind);
	}
	public CompletableFuture<Session> connect(SocketAddress address) {
		return connect(address, Optional.empty());
	}

	// ==============================================================================================
	// セッションの構築
	// ==============================================================================================
	/**
	 * 指定された Wire 上で新規のセッションを構築しメッセージング処理を開始します。
	 * このメソッドを使用することで `listen()`, `connect()` によるネットワーク以外の `Wire` 実装を使用すること
	 * が出来ます。
	 * @param wire セッションに結びつける Wire
	 * @return 新規セッション
	 */
	public Session bind(Wire wire){
		logger.trace("bind(" + wire + "):" + name);
		Session session = new Session(this, name + "[" + wire.getPeerAddress().toString() + "]", service, wire);
		add(sessions, session);
		session.onClosed.add( s -> remove(sessions, s));
		return session;
	}

	// ==============================================================================================
	// ノードのシャットダウン
	// ==============================================================================================
	/**
	 * このノードの処理を終了します。ノード上でアクティブなすべてのサーバ及びセッションがクローズされます。
	 */
	public void shutdown() {
		servers.get().forEach(NetworkBridge.Server::close);
		sessions.get().forEach(Session::close);
		logger.debug("shutting-down " + name + "; all available " + sessions.get().size() + " sessions, " + servers.get().size() + "servers are closed");
	}


	/**
	 * 新規のノードを生成するためのビルダーを作成します。
	 * @param name 新しく作成するノードの名前
	 */
	public static Builder apply(String name){
		return new Builder(name);
	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// Builder
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * 新規のノードを構築するためのビルダークラスです。
	 */
	public static class Builder {
		private final String name;
		private Service service = new Service(){ };
		private NetworkBridge bridge = new Netty();
		private Codec codec = MessagePackCodec.getInstance();

		private Builder(String name){
			this.name = name;
		}

		/**
		 * 新しく生成するノードが使用するブリッジを指定します。
		 */
		public Builder bridge(NetworkBridge bridge){
			this.bridge = bridge;
			return this;
		}

		/**
		 * ノードが接続を受け新しいセッションの発生した初期状態でリモートのピアに提供するサービスを指定します。
		 * このサービスはセッション構築後にセッションごとに変更可能です。
		 * @param service 初期状態のサービス
		 */
		public Builder serve(Service service){
			this.service = service;
			return this;
		}

		/**
		 * 新しく生成するノードが使用するコーデックを指定します。
		 */
		public Builder codec(Codec codec){
			this.codec = codec;
			return this;
		}

		/**
		 * このビルダーに設定されている内容で新しいノードのインスタンスを構築します。
		 */
		public LocalNode build() {
			return new LocalNode(name, service, bridge, codec);
		}

	}

	/**
	 * 指定されたコンテナに要素を追加するための再帰関数。
	 */
	private static <T> void add(AtomicReference<Collection<T>> container, T element) {
		Collection<T> n = container.get();
		Collection<T> newCollection = new ArrayList<>(n);
		newCollection.add(element);
		if(! container.compareAndSet(n, newCollection)){
			add(container, element);
		}
	}

	/**
	 * 指定されたコンテナから要素を除去するための再帰関数。
	 */
	private static <T> void remove(AtomicReference<Collection<T>> container, T element) {
		Collection<T> oldContainer = container.get();
		Collection<T> newContainer = new ArrayList<>(oldContainer);
		if(newContainer.remove(element) && ! container.compareAndSet(oldContainer, newContainer)){
			remove(container, element);
		}
	}

	public ConnectConfig prepareConnect(){
		return new ConnectConfig();
	}

	public class ConnectConfig {
		private int pingInterval = 10 * 1000;
		private SocketAddress address;
		private Optional<SSLContext> tls = Optional.empty();
		private ConnectConfig(){ }
		public ConnectConfig ping(int ping){
			this.pingInterval = ping;
			return this;
		}
		public ConnectConfig address(SocketAddress address){
			this.address = address;
			return this;
		}
		public ConnectConfig tls(SSLContext tls){
			this.tls = Optional.of(tls);
			return this;
		}
		public CompletableFuture<Session> connect(){
			return LocalNode.this.connect(this);
		}
	}

	public class ListenConfig {
		private int maxPing = 10 * 1000;
		private int minPing = 1 * 1000;
		private Optional<Integer> ping = Optional.empty();
		private SocketAddress address;
		private Optional<SSLContext> tls = Optional.empty();
		private ListenConfig(){ }
		public ListenConfig maxPing(int maxPing){
			this.maxPing = maxPing;
			return this;
		}
		public ListenConfig minPing(int minPing){
			this.minPing = minPing;
			return this;
		}
		public ListenConfig ping(int ping){
			this.ping = ping > 0? Optional.of(ping): Optional.empty();
			return this;
		}
		public ListenConfig address(SocketAddress address){
			this.address = address;
			return this;
		}
		public ListenConfig tls(SSLContext tls){
			this.tls = Optional.of(tls);
			return this;
		}
		public CompletableFuture<NetworkBridge.Server> listen(){
			return LocalNode.this.listen(this);
		}
	}

}
