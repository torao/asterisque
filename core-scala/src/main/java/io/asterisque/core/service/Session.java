package io.asterisque.core.service;

import io.asterisque.Asterisque;
import io.asterisque.Node;
import io.asterisque.Priority;
import io.asterisque.core.Debug;
import io.asterisque.core.ProtocolException;
import io.asterisque.core.codec.TypeVariableCodec;
import io.asterisque.core.msg.*;
import io.asterisque.core.util.EventHandlers;
import io.asterisque.core.wire.Wire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * ピアとの通信状態を表すクラスです。
 * <p>
 * ピア間の通信において役割の衝突を避ける目的で、便宜的に接続受付側をプライマリ (primary)、通信開始側をセカンダリ (secondary)
 * と定めます。
 * <p>
 * セッションはピアとのメッセージ送受信のための {@code Wire} を持ちます (Wire を持たない状態は物理的に接続して
 * いない状態を表します)。セッションは Wire が切断されると新しい Wire の接続を試みます。
 * <p>
 * セッション ID はサーバ側
 * セッションが構築されると {@link SyncConfig 状態同期} のための
 * {@link Control} メッセージがクライアントから送信されます。サーバはこのメッセージを受け
 * ると新しいセッション ID を発行して状態同期の {@link Control} メッセージで応答し、双方の
 * セッションが開始します。
 *
 * @author Takami Torao
 */
public class Session {
  private static final Logger logger = LoggerFactory.getLogger(Session.class);

  /**
   * このセッションの ID。初期状態で {@link Asterisque#Zero} でありセッションが確立したときに有効な値が設定される。
   */
  @Nonnull
  public final UUID id;

  /**
   * このセッションを使用しているノード。
   */
  @Nonnull
  public final Node node;

  /**
   * peer 間においてこのセッションが primary の場合に true を示す。
   */
  public final boolean isPrimary;

  /**
   * このセッション上で使用する RPC のパラメータや返値を転送可能型に変換するコンバーター。
   */
  @Nonnull
  private final TypeVariableCodec conversion;

  /**
   * このセッションが使用している Wire から受信した状態同期メッセージ。
   */
  @Nullable
  private final SyncConfig config;

  /**
   * このセッションがクローズ済みかを表すフラグ。
   */
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private final Wire wire;

  private final PipeSpace pipes;

  /**
   * このセッションがクローズされたときに呼び出されるイベントハンドラです。
   */
  public final EventHandlers<Session> onClosed = new EventHandlers<>();

  Session(@Nonnull Node node, @Nonnull Wire wire, @Nonnull UUID id, @Nonnull TypeVariableCodec conversion, @Nonnull SyncConfig config) {
    this.id = id;
    this.node = node;
    this.wire = wire;
    this.isPrimary = wire.isPrimary();
    this.conversion = conversion;
    this.pipes = new PipeSpace(this);
    this.config = config;

    logger.debug("{}: session created, waiting sync-config", logId());

    // クライアントであればヘッダの送信
    if (!isPrimary) {
      // ※サーバ側からセッションIDが割り当てられていない場合は Zero が送信される
      int ping = options.get(Options.KEY_PING_REQUEST).get();
      int timeout = options.get(Options.KEY_SESSION_TIMEOUT_REQUEST).get();
      SyncConfig header = new SyncConfig(node.id, Asterisque.Zero, System.currentTimeMillis(), ping, timeout);
      post(header.toControl());
    }
  }

  /**
   * このセッションの peer アドレスを参照します。ピアと接続していない場合は Optional.empty() を返します。
   */
  @Nonnull
  public Optional<InetAddress> remote() {
    return Optional.ofNullable(wire)
        .flatMap(w -> Optional.ofNullable((InetSocketAddress) w.remote()))
        .map(InetSocketAddress::getAddress);
  }

  /**
   * @return このセッションがクローズを完了している場合 true
   */
  public boolean closed() {
    return closed.get();
  }

  /**
   * このセッションを好意的にクローズします。このメソッドは {@code close(true)} と等価です。
   */
  public void close() {
    close(true);
  }

  /**
   * このセッションをクローズします。
   * このセッションが使用している {@link Wire} および実行中のすべての {@link Pipe} はクローズされ、以後のメッセージ配信は
   * 行われなくなります。
   */
  public void close(boolean graceful) {
    if (closed.compareAndSet(false, true)) {
      logger.debug("{}: closing session with {}", logId(), graceful ? "graceful" : "force");

      // 残っているすべてのパイプに Close メッセージを送信
      pipes.close(graceful);

      // Close 制御メッセージを送信
      if (graceful) {
        post(new Control(Control.Close));
      }

      // 以降のメッセージ送信をすべて例外に変更して送信を終了
      // ※Pipe#close() で Session#post() が呼び出されるためすべてのパイプに Close を投げた後に行う

      // Wire のクローズ
      wire.close();

      // セッションのクローズを通知
      onClosed.accept(this);
    }
  }

  // ==============================================================================================
  // パイプのオープン
  // ==============================================================================================

  /**
   * このセッション上のピアに対して指定された function との非同期呼び出しのためのパイプを作成します。
   *
   * @param priority           新しく生成するパイプの同一セッション内でのプライオリティ
   * @param function           function の識別子
   * @param params             function の実行パラメータ
   * @param onTransferComplete 呼び出し先とのパイプが生成されたときに実行される処理
   * @return パイプに対する Future
   */
  public CompletableFuture<Object> open(byte priority, short function, @Nonnull Object[] params,
                                        @Nonnull Function<Pipe, CompletableFuture<Object>> onTransferComplete) {
    Pipe pipe = pipes.create(priority, function);
    pipe.open(params);
    return Pipe.using(pipe, () -> onTransferComplete.apply(pipe));
  }

  public CompletableFuture<Object> open(byte priority, short function, Object[] params) {
    return open(priority, function, params, pipe -> pipe.future);
  }

  // ==============================================================================================
  // メッセージの配信
  // ==============================================================================================

  /**
   * 指定されたメッセージを受信したときに呼び出されその種類によって処理を分岐します。
   */
  private void deliver(Message msg) {
    if (logger.isTraceEnabled()) {
      logger.trace(logId() + ": deliver: " + msg);
    }
    ensureSessionStarted(msg);

    // Control メッセージはこのセッション内で処理する
    if (msg instanceof Control) {
      Control ctrl = (Control) msg;
      switch (ctrl.code) {
        case Control.SyncConfig:
          sync(SyncConfig.parse(ctrl));
          break;
        case Control.Close:
          close(false);
          break;
        default:
          logger.error(id() + ": unsupported control code: 0x" + Integer.toHexString(ctrl.code & 0xFF));
          throw new ProtocolViolationException("unsupported control");
      }
      return;
    }

    // メッセージの配信先パイプを参照
    Optional<Pipe> _pipe;
    if (msg instanceof Open) {
      _pipe = pipes.create((Open) msg);
    } else {
      _pipe = pipes.get(msg.pipeId);
    }

    // パイプが定義されていない場合
    if (!_pipe.isPresent()) {
      if (msg instanceof Close) {
        logger.debug(logId() + ": both of sessions unknown pipe #" + msg.pipeId);
      } else if (msg instanceof Open) {
        post(Priority.Normal, Close.unexpectedError(msg.pipeId, "duplicate pipe-id specified: " + msg.pipeId));
      } else if (msg instanceof Block) {
        logger.debug(logId() + ": unknown pipe-id: " + msg);
        post(Priority.Normal, Close.unexpectedError(msg.pipeId, "unknown pipe-id specified: " + msg.pipeId));
      }
      return;
    }

    Pipe pipe = _pipe.get();
    try {
      if (msg instanceof Open) {
        // サービスを起動しメッセージポンプの開始
        Open open = (Open) msg;
        service.dispatch(pipe, open, logId(), codec);
      } else if (msg instanceof Block) {
        // Open は受信したがサービスの処理が完了していない状態でメッセージを受信した場合にパイプのキューに保存できるよう
        // 一度パイプを経由して deliver(Pipe,Message) を実行する
        pipe.dispatchBlock((Block) msg);
      } else if (msg instanceof Close) {
        pipe.close((Close) msg);
      } else {
        throw new IllegalStateException("unexpected message: " + msg);
      }
    } catch (Throwable ex) {
      logger.error(id() + ": unexpected error: " + msg + ", wsClosed pipe " + pipe, ex);
      post(pipe.priority, Close.unexpectedError(msg.pipeId, "internal error"));
      if (ex instanceof ThreadDeath) {
        throw (ThreadDeath) ex;
      }
    }
  }


  /**
   * @return ping 間隔 (秒)。接続していない場合は 0。
   */
  public int getPingInterval() {
    return config.map(h -> {
      if (isPrimary) {
        int maxPing = options.get(Options.KEY_MAX_PING).get();
        int minPing = options.get(Options.KEY_MIN_PING).get();
        return Math.min(maxPing, Math.max(minPing, h.ping));
      } else {
        return h.ping;
      }
    }).orElse(0);
  }

  /**
   * @return セッションタイムアウト (秒)。接続していない場合は 0。
   */
  public int getTimeout() {
    return config.map(h -> {
      if (isPrimary) {
        int maxSession = options.get(Options.KEY_MAX_SESSION_TIMEOUT).get();
        int minSession = options.get(Options.KEY_MIN_SESSION_TIMEOUT).get();
        return Math.min(maxSession, Math.max(minSession, h.sessionTimeout));
      } else {
        return h.sessionTimeout;
      }
    }).orElse(0);
  }

  // ==============================================================================================
  // セッション同期の実行
  // ==============================================================================================

  /**
   * 新たな Wire 接続から受信したストリームヘッダでこのセッションの内容を同期化します。
   */
  private void sync(SyncConfig header) throws ProtocolViolationException {
    if (this.config.isPresent()) {
      throw new ProtocolViolationException("multiple sync message");
    }
    this.config = Optional.of(header);
    if (isPrimary) {
      // サーバ側の場合は応答を返す
      if (header.sessionId.equals(Asterisque.Zero)) {
        // 新規セッションの開始
        this.id = node.repository.nextUUID();
        logger.trace(logId() + ": new session-id is issued: " + this.id);
        SyncConfig ack = new SyncConfig(
            Asterisque.Protocol.Version_0_1, node.id, id, System.currentTimeMillis(), getPingInterval(), getTimeout());
        post(Priority.Normal, ack.toControl());
      } else {
        Optional<Principal> principal = Optional.empty();
        try {
          principal = Optional.of(wire.get().getSSLSession().get().getPeerPrincipal());
        } catch (SSLPeerUnverifiedException ex) {
          // TODO クライアント認証が無効な場合? 動作確認
          logger.debug(logId() + ": client authentication ignored: " + ex);
        }
        // TODO リポジトリからセッション?サービス?を復元する
        Optional<byte[]> service = node.repository.loadAndDelete(principal, header.sessionId);
        if (service.isPresent()) {
          SyncConfig ack = new SyncConfig(
              Asterisque.Protocol.Version_0_1, node.id, id, System.currentTimeMillis(), getPingInterval(), getTimeout());
          post(Priority.Normal, ack.toControl());
        } else {
          // TODO retry after
          post(Priority.Normal, new Control(Control.Close));
        }
      }
    } else {
      // クライアントの場合はサーバが指定したセッション ID を保持
      if (header.sessionId.equals(Asterisque.Zero)) {
        throw new ProtocolViolationException("session-id is not specified from server: " + header.sessionId);
      }
      if (this.id.equals(Asterisque.Zero)) {
        this.id = header.sessionId;
        logger.trace(logId() + ": new session-id is specified: " + this.id);
      } else if (!this.id.equals(header.sessionId)) {
        throw new ProtocolViolationException("unexpected session-id specified from server: " + header.sessionId + " != " + this.id);
      }
    }
    logger.info(logId() + ": sync-configuration success, beginning session");
    onSync.accept(this, header.sessionId);
  }

  // ==============================================================================================
  // メッセージの送信
  // ==============================================================================================

  /**
   * ピアに対して指定されたメッセージを送信します。
   */
  void post(Message msg) {
    if (closed() && !(msg instanceof Control)) {
      logger.error("session {} has been closed, message is discarded: {}", id, msg);
    } else {
      wire.outbound.offer(msg);
    }
  }

  void destroy(short pipeId) {
    pipes.destroy(pipeId);
  }

  // ==============================================================================================
  // リモートインターフェースの参照
  // ==============================================================================================

  /**
   * このセッションの相手側となるインターフェースを参照します。
   */
  public <T> T bind(Class<T> clazz) {
    return clazz.cast(java.lang.reflect.Proxy.newProxyInstance(
        Thread.currentThread().getContextClassLoader(),
        new Class[]{clazz}, new Skeleton(clazz)
    ));
  }

  // ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
  // Skeleton
  // ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

  /**
   * リモート呼び出し先の function を @Export 定義されたメソッドとして扱うための動的プロキシ用ハンドラ。
   */
  private class Skeleton implements InvocationHandler {

    public Skeleton(Class<?> clazz) {
      // 指定されたインターフェースのすべてのメソッドに @Export アノテーションが付けられていることを確認
      Optional<String> methods = Stream.of(clazz.getDeclaredMethods())
          .filter(m -> m.getAnnotation(Export.class) == null)
          .map(Debug::getSimpleName).reduce((a, b) -> a + "," + b);
      if (methods.isPresent()) {
        throw new IllegalArgumentException(
            "@" + Export.class.getSimpleName() + " annotation is not specified on: " + methods.get());
      }
      // 指定されたインターフェースの全てのメソッドが CompletableFuture の返値を持つことを確認
      // TODO Scala の Future も許可したい
      Stream.of(clazz.getDeclaredMethods())
          .filter(m -> !m.getReturnType().equals(CompletableFuture.class))
          .map(Debug::getSimpleName).reduce((a, b) -> a + "," + b).ifPresent(name -> {
        throw new IllegalArgumentException(
            "methods without return-type CompletableFuture<?> exists: " + name);
      });
    }

    // ============================================================================================
    // リモートメソッドの呼び出し
    // ============================================================================================

    /**
     * リモートメソッドを呼び出します。
     *
     * @param proxy  プロキシオブジェクト
     * @param method 呼び出し対象のメソッド
     * @param args   メソッドの引数
     * @return 返し値
     */
    public Object invoke(Object proxy, Method method, Object[] args) throws InvocationTargetException, IllegalAccessException {
      Export export = method.getAnnotation(Export.class);
      if (export == null) {
        // toString() や hashCode() など Object 型のメソッド呼び出し?
        logger.debug(logId() + ": normal method: " + Debug.getSimpleName(method));
        return method.invoke(this, args);
      } else {
        // there is no way to receive block in interface binding
        logger.debug(logId() + ": calling remote method: " + Debug.getSimpleName(method));
        byte priority = export.priority();
        short function = export.value();
        return open(priority, function, (args == null ? new Object[0] : args));
      }
    }

  }

  @Override
  public String toString() {
    return id().toString();
  }

  String logId() {
    return Asterisque.logPrefix(isPrimary, id());
  }

  /**
   * このセッションが開始していることを保証する処理。
   *
   * @param msg 受信したメッセージ
   */
  private void ensureSessionStarted(@Nonnull Message msg) {
    if (config != null && (!(msg instanceof Control) || ((Control) msg).code != Control.SyncConfig)) {
      logger.error("{}: unexpected message received; session is not initialized yet: {}", id, msg);
      throw new ProtocolException("unexpected message received");
    }
  }

}
