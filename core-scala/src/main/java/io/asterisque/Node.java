/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */
package io.asterisque;

import io.asterisque.cluster.Repository;
import io.asterisque.core.Debug;
import io.asterisque.core.codec.VariableCodec;
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
// Node
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

/**
 * @author Takami Torao
 */
public class Node {
  private static final Logger logger = LoggerFactory.getLogger(Node.class);

  /**
   * いかなる function もピアに公開していないデフォルト状態のサービスです。
   */
  private static final Service DefaultService = new Service() {
  };

  /**
   * このノード上で新しいセッションが発生した時に初期状態で使用するサービス。
   */
  private volatile Service service;

  /**
   * このノード上で Listen しているすべてのサーバ。ノードのシャットダウン時にクローズされる。
   */
  private final Collection<Server> servers = new LinkedList<>();

  /**
   * このノード上で使用されているすべてのセッション。ノードのシャットダウン時にクローズされる。
   */
  private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

  /**
   * このノードの ID です。
   */
  public final UUID id;

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

  public final VariableCodec codec = new VariableCodec();

  private final AtomicBoolean closing = new AtomicBoolean(false);

  // ==============================================================================================
  // コンストラクタ
  // ==============================================================================================

  /**
   * @param name       ノード名
   * @param executor   このノードでの処理を実行するスレッドプール
   * @param service    ノードで発生したセッションのデフォルトのサービス
   * @param repository セッションを待避するリポジトリ
   */
  public Node(UUID id, String name, ExecutorService executor, Service service, Repository repository) {
    this.id = id;
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

//  public CompletableFuture<Bridge.Server> listen(SocketAddress address, Options options, Consumer<Session> onAccept) {
//    logger.trace(Asterisque.logPrefix(true) + ": listen(" + Debug.toString(address) + "," + options + ",onAccept)");
//    Bridge bridge = options.get(Options.KEY_BRIDGE).get();
//    return bridge.newServer(this, address, options, wire -> {
//      CompletableFuture<Wire> future = CompletableFuture.completedFuture(wire);
//      Optional<CompletableFuture<Wire>> of = Optional.of(future);
//      AtomicReference<Optional<CompletableFuture<Wire>>> ref = new AtomicReference<>(of);
//      new Session(this, true, codec, service, options,
//          () -> ref.getAndSet(Optional.empty()),
//          (Session _session, UUID id) -> {
//            sessions.put(id, _session);
//            _session.onClosed.add(s -> sessions.remove(id));
//            logger.debug(_session.logId() + ": onAccept() callback: " + Debug.toString(address));
//            onAccept.accept(_session);
//          });
//    });
//  }

  public CompletableFuture<Session> connect(SocketAddress remote, Options options) {
    logger.trace(Asterisque.logPrefix(false) + ": connect(" + remote + "," + options + ")");
    Bridge bridge = options.get(Options.KEY_BRIDGE).get();
    CompletableFuture<Session> future = new CompletableFuture<>();
    new Session(this, false, codec, service, options,
        () -> Optional.of(bridge.newWire(this, remote, options)),
        (Session _session, UUID id) -> {
          sessions.put(id, _session);
          _session.onClosed.add(s -> sessions.remove(id));
          future.complete(_session);
        }
    );
    return future;
  }

  // ==============================================================================================
  // ノードのシャットダウン
  // ==============================================================================================

  /**
   * このノードの処理を終了します。ノード上でアクティブなすべてのサーバ及びセッションがクローズされます。
   */
  public void shutdown() {
    if (closing.compareAndSet(false, true)) {
      logger.debug("shutting-down node \"" + name + "\";" +
          " all available " + sessions.size() + " sessions, " + servers.size() + " servers will be closed");
      executor.shutdown();
      servers.forEach(Server::close);
      servers.clear();
      sessions.values().forEach(Session::close);
      sessions.clear();
    }
  }

}
