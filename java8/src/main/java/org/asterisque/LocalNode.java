/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

import io.asterisque.Service;
import io.asterisque.Session;
import org.asterisque.cluster.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

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
	private final Collection<NetworkBridge.Server> servers = new LinkedList<>();

	/**
	 * このノード上で使用されているすべてのセッション。ノードのシャットダウン時にクローズされる。
	 */
	private final Collection<Session> sessions = new LinkedList<>();

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
			servers.forEach(NetworkBridge.Server::close);
			servers.clear();
			sessions.forEach(Session::close);
			sessions.clear();
		}
	}

}
