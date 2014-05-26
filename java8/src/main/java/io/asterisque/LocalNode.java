/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque;

import io.asterisque.cluster.Repository;
import io.asterisque.conf.ConnectConfig;
import io.asterisque.conf.ListenConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
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

	private static final Service DefaultService = new Service(){ };

	public final String name;

	private final ExecutorService executor;

	/**
	 * この
	 */
	private final Repository repository;

	public LocalNode(String name, Service initService, ExecutorService executor, Repository repository){
		this.name = name;
		this.service = initService;
		this.executor = executor;
		this.repository = repository;
	}

	public LocalNode(String name){
		this(name, DefaultService, Executors.newCachedThreadPool(), Repository.OnMemory);
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
	 * @param config listen 設定
	 * @param onAccept 新規接続を受け付けた時に実行する処理
	 * @return Server の Future
	 */
	public ServerSession listen(ListenConfig config, Consumer<Session> onAccept) {
		config.verify();
		CompletableFuture<NetworkBridge.Server> future = new CompletableFuture<>();
		NetworkBridge bridge = config.bridge();
		bridge.listen(config, executor, wire -> onAccept.accept(bind(wire))).whenComplete((server, ex) -> {
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
		return new ServerSession(repository, future);
	}
	public ServerSession listen(ListenConfig config) {
		return listen(config, s -> { });
	}

	// ==============================================================================================
	// ノードへの接続
	// ==============================================================================================
	/**
	 * このノードから指定されたアドレスの別のノードへ接続を行います。
	 *
	 * @param config 接続設定
	 * @return 接続により発生した Session の Future
	 */
	public Session connect(ConnectConfig config) {
		config.verify();
		return bind((onReceive, onClose) -> {
			NetworkBridge bridge = config.bridge();
			return bridge.connect(config, executor, onReceive, onClose);
		});
	}

	// ==============================================================================================
	// セッションの構築
	// ==============================================================================================
	/**
	 * 既存の Wire 上で新規のセッションを構築しメッセージング処理を開始します。
	 * このメソッドを使用することで `listen()`, `connect()` によるネットワーク以外の `Wire` 実装を使用すること
	 * が出来ます。
	 * @param wire セッションに結びつける Wire
	 * @return 新規セッション
	 */
	public Session bind(Wire wire){
		return bind((onReceive, onClose) -> CompletableFuture.completedFuture(wire));
	}

	// ==============================================================================================
	// セッションの構築
	// ==============================================================================================
	/**
	 * 再接続可能なラムダから生成される Wire を使用する新規のセッションを構築します。
	 * このメソッドを使用することで `listen()`, `connect()` によるネットワーク以外の `Wire` 実装を使用すること
	 * が出来ます。
	 * セッションは内部的に再接続をおこなうためラムダは複数回呼び出されます。
	 * @param wireFactory Wire ファクトリ
	 * @return 新規セッション
	 */
	public Session bind(BiFunction<Consumer<Message>, Consumer<Wire>, CompletableFuture<Wire>> wireFactory){
		logger.trace("bind():" + name);
		Session session = new Session(this, name, service, wireFactory);
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
		executor.shutdown();
		servers.get().forEach(NetworkBridge.Server::close);
		servers.set(Collections.emptyList());
		sessions.get().forEach(Session::close);
		sessions.set(Collections.emptyList());
		logger.debug("shutting-down " + name + "; all available " + sessions.get().size() + " sessions, " + servers.get().size() + "servers are closed");
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


	/*
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
	*/

	/*
	public class ListenConfig {
		private int maxPing = 10 * 1000;
		private int minPing = 1 * 1000;
		private Optional<Integer> ping = Optional.empty();
		private SocketAddress address;
		private Optional<SSLContext> tls = Optional.empty();
		private ListenConfig(){ }
		public int maxPing(){ return maxPing; }
		public ListenConfig maxPing(int maxPing){
			this.maxPing = maxPing;
			return this;
		}
		public int minPing(){ return minPing; }
		public ListenConfig minPing(int minPing){
			this.minPing = minPing;
			return this;
		}
		public Optional<Integer> ping(){ return ping; }
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
	*/

}
