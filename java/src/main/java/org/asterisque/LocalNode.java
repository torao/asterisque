/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

import org.asterisque.cluster.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
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
	 * ピアへいかなるファンクションも公開していないデフォルト状態のサービスです。
	 */
	private static final Service DefaultService = new Service(){ };

	/**
	 * このノード上で新しいセッションが発生した時に初期状態で使用するサービス。
	 */
	private volatile Service service;

	/**
	 * このノード上で Listen しているすべてのサーバ。ノードのシャットダウン時にクローズされる。
	 */
	private final Collection<Bridge.Server> servers = new LinkedList<>();

	/**
	 * このノード上で使用されているすべてのセッション。ノードのシャットダウン時にクローズされる。
	 */
	private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

	/**
	 * このノードの名前です。
	 */
	public final String name;

	/**
	 * このノードの処理で使用するスレッドプールです。
	 */
	public final ExecutorService executor;

	/**
	 * この
	 */
	public final Repository repository;

	private final AtomicBoolean closing = new AtomicBoolean(false);

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 *
	 * @param name ノード名
	 * @param executor このノードでの処理を実行するスレッドプール
	 * @param service ノードで発生したセッションのデフォルトのサービス
	 * @param repository セッションを待避するリポジトリ
	 */
	public LocalNode(String name, ExecutorService executor, Service service, Repository repository){
		this.name = name;
		this.executor = executor;
		this.service = service;
		this.repository = repository;
	}

	// ==============================================================================================
	// デフォルトサービスの変更
	// ==============================================================================================
	/**
	 * このノード上で新しいセッションが発生した時のデフォルトのサービスを変更します。
	 *
	 * @param newService 新しいサービス
	 * @return 現在設定されているサービス
	 */
	public Service setService(Service newService) {
		Service old = service;
		service = newService;
		return old;
	}

	public CompletableFuture<Bridge.Server> listen(SocketAddress address, Options options, Consumer<Session> onAccept) {
		Bridge bridge = options.get(Options.KEY_BRIDGE).get();
		return bridge.newServer(this, address, options, wire -> {
			UUID id = repository.nextUUID();
			CompletableFuture<Wire> future = CompletableFuture.completedFuture(wire);
			Optional<CompletableFuture<Wire>> of = Optional.of(future);
			AtomicReference<Optional<CompletableFuture<Wire>>> ref = new AtomicReference<>(of);
			Session session = new Session(id, this, true, service, options, () -> ref.getAndSet(Optional.empty()));
			sessions.put(id, session);
			session.onClosed.add(s -> sessions.remove(id));
			onAccept.accept(session);
		});
	}

	public Session connect(SocketAddress remote, Options options){
		Bridge bridge = options.get(Options.KEY_BRIDGE).get();
		UUID id = repository.nextUUID();
		Session session = new Session(id, this, false, service, options, () -> Optional.of(bridge.newWire(this, remote, options)));
		sessions.put(id, session);
		session.onClosed.add(s -> sessions.remove(id));
		return session;
	}

	// ==============================================================================================
	// ノードのシャットダウン
	// ==============================================================================================
	/**
	 * このノードの処理を終了します。ノード上でアクティブなすべてのサーバ及びセッションがクローズされます。
	 */
	public void shutdown() {
		if(closing.compareAndSet(false, true)){
			logger.debug("shutting-down " + name + ";" +
				" all available " + sessions.size() + " sessions, " + servers.size() + "servers will be closed");
			executor.shutdown();
			servers.forEach(Bridge.Server::close);
			servers.clear();
			sessions.values().forEach(Session::close);
			sessions.clear();
		}
	}

}
