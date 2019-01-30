package io.asterisque.core.session;

import io.asterisque.Asterisque;
import io.asterisque.core.Debug;
import io.asterisque.core.ProtocolException;
import io.asterisque.core.codec.VariableCodec;
import io.asterisque.core.msg.*;
import io.asterisque.core.service.Export;
import io.asterisque.core.util.Latch;
import io.asterisque.core.wire.MessageQueue;
import io.asterisque.core.wire.Wire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 一つの {@link Wire} 上で行われるピアとの通信状態を表すクラスです。
 * <p>
 * ピア間の通信において役割の衝突を避ける目的で、便宜的に接続受付側をプライマリ (primary)、通信開始側をセカンダリ (secondary)
 * と定めます。
 * <p>
 * セッションはピアとのメッセージ送受信のための {@code Wire} を持ちます (Wire を持たない状態は物理的に接続して
 * いない状態を表します)。セッションは Wire が切断されると新しい Wire の接続を試みます。
 * <p>
 * セッション ID はサーバ側
 * セッションが構築されると {@link SyncSession 状態同期} のための
 * {@link Control} メッセージがクライアントから送信されます。サーバはこのメッセージを受け
 * ると新しいセッション ID を発行して状態同期の {@link Control} メッセージで応答し、双方の
 * セッションが開始します。
 *
 * @author Takami Torao
 */
public class Session {
  private static final Logger logger = LoggerFactory.getLogger(Session.class);

  /**
   * このセッションの ID。
   */
  @Nonnull
  private final UUID id;

  /**
   * このセッション上で使用する RPC のパラメータや返値を転送可能型に変換するコンバーター。
   */
  @Nonnull
  private final VariableCodec codec;

  /**
   * このセッションを生成するために Wire に対して送受信した状態同期メッセージ。
   */
  @Nonnull
  private final SyncSession.Pair sync;

  /**
   * このセッションがクローズ済みかを表すフラグ。
   */
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * このセッションの使用している {@code Wire}。
   */
  private final Wire wire;

  private final PipeSpace pipes;

  /**
   * このセッションが使用する {@link Dispatcher}。
   */
  private final Dispatcher dispatcher;

  private final String serviceId;

  /**
   * {@link Wire#outbound} のメッセージ流入を調整するためのラッチです。
   */
  private final Latch outboundLatch = new Latch();

  /**
   * このセッションがクローズされたときに呼び出されるイベントハンドラです。
   */
  private final List<Listener> listeners = new ArrayList<>();

  /**
   * ログメッセージに付与する識別子。
   */
  private final String logId;

  Session(@Nonnull UUID id, @Nonnull Dispatcher dispatcher, @Nonnull Wire wire, @Nonnull VariableCodec codec, @Nonnull SyncSession.Pair sync) {
    if (wire.remote() == null) {
      throw new IllegalStateException("remote address of wire has not been confirmed");
    }
    this.id = id;
    this.dispatcher = dispatcher;
    this.wire = wire;
    this.codec = codec;
    this.sync = sync;
    this.pipes = new PipeSpace(this);
    this.serviceId = (wire.isPrimary() ? sync.primary : sync.secondary).serviceId;

    this.wire.outbound.addListener(new MessageQueue.Listener() {
      @Override
      public void messageOfferable(@Nonnull MessageQueue messageQueue, boolean offerable) {
        if (offerable) {
          outboundLatch.open();
        } else {
          outboundLatch.close();
        }
      }
    });

    this.wire.inbound.addListener(new MessageQueue.Listener() {
      @Override
      public void messagePollable(@Nonnull MessageQueue messageQueue, boolean pollable) {
        if(pollable){
          Message msg = messageQueue.poll();
          while(msg != null){
            deliver(msg);
            msg = messageQueue.poll();
          }
        }
      }
    });

    this.logId = Asterisque.logPrefix(wire.isPrimary(), id);

    logger.debug("{}: session created", logId);
  }

  /**
   * このセッションの ID を参照します。この ID は同じノード上に存在するセッションに対してユニークです。
   *
   * @return セッションID
   */
  @Nonnull
  public UUID id() {
    return id;
  }

  /**
   * このセッションが peer に対してプライマリかを参照します。この判定値は P2P での役割を決めるために使用
   * されます。
   *
   * @return プライマリの場合 true、セカンダリの場合 false
   */
  public boolean isPrimary() {
    return wire.isPrimary();
  }

  @Nonnull
  public String serviceId() {
    return serviceId;
  }

  @Nonnull
  public VariableCodec codec() {
    return codec;
  }

  /**
   * このセッションが接続している peer の IP アドレスを参照します。
   *
   * @return リモート IP アドレス
   */
  @Nonnull
  public InetSocketAddress remote() {
    InetSocketAddress address = (InetSocketAddress) wire.remote();
    if (address == null) {
      throw new IllegalStateException("remote address of wire has not been confirmed");
    }
    return address;
  }

  @Nonnull
  public SyncSession.Pair config() {
    return sync;
  }

  public void addListener(@Nonnull Listener listener) {
    this.listeners.add(listener);
  }

  public void removeListener(@Nonnull Listener listener) {
    this.listeners.remove(listener);
  }

  /**
   * このセッションがクローズされているかを参照します。
   *
   * @return セッションがクローズを完了している場合 true
   */
  public boolean closed() {
    return closed.get();
  }

  /**
   * このセッションを graceful にクローズします。このメソッドは {@code close(true)} と等価です。
   */
  public void close() {
    close(true);
  }

  /**
   * このセッションをクローズします。
   * セッションが使用している {@link Wire} および実行中のすべての {@link Pipe} はクローズされ、以後のメッセージ配信は
   * 行われなくなります。
   */
  public void close(boolean graceful) {
    if (closed.compareAndSet(false, true)) {
      logger.debug("{}: this session is closing in {}", logId, graceful ? "gracefully" : "forcibly");

      // 残っているすべてのパイプに Close メッセージを送信
      pipes.close(graceful);

      // パイプクローズの後に Close 制御メッセージをポスト
      if (graceful) {
        post(new Control(Control.Close));
      }

      // 以降のメッセージ送信をすべて例外に変更して送信を終了
      // ※Pipe#close() で Session#post() が呼び出されるためすべてのパイプに Close を投げた後に行う

      // Wire のクローズ
      wire.close();

      // セッションのクローズを通知
      listeners.forEach(listener -> listener.sessionClosed(this));
    }
  }

  /**
   * このセッション上のピアに対して指定された function との非同期呼び出しのためのパイプを作成します。
   *
   * @param priority           新しく生成するパイプの同一セッション内でのプライオリティ
   * @param function           function の識別子
   * @param params             function の実行パラメータ
   * @param onTransferComplete 呼び出し先とのパイプが生成されたときに実行される処理
   * @return オープンしたパイプでの処理の実行結果を通知する Future
   */
  public CompletableFuture<Object> open(byte priority, short function, @Nonnull Object[] params,
                                        @Nonnull Function<Pipe, CompletableFuture<Object>> onTransferComplete) {
    Pipe pipe = pipes.create(priority, function);
    pipe.open(params);
    return onTransferComplete.apply(pipe);
  }

  public CompletableFuture<Object> open(byte priority, short function, @Nonnull Object[] params) {
    return open(priority, function, params, Pipe::future);
  }

  /**
   * ピアからメッセージを受信したときに呼び出されます。メッセージの種類によって処理を分岐します。
   *
   * @param msg 受信したメッセージ
   */
  private void deliver(Message msg) {
    logger.trace("{}: deliver: {}", logId, msg);

    // Control メッセージはこのセッション内で処理する
    if (msg instanceof Control) {
      Control ctrl = (Control) msg;
      switch (ctrl.code) {
        case Control.Close:
          close(false);
          break;
        case Control.SyncSession:
          // SYNC_SESSION はセッション構築前に処理されているはず
        default:
          logger.error("{}: unsupported control code: 0x{}", logId, Integer.toHexString(ctrl.code & 0xFF));
          logger.error("{}: {}", logId, msg);
          throw new ProtocolViolationException("unsupported control");
      }
      return;
    }

    // Open メッセージを受信した場合
    if (msg instanceof Open) {
      Pipe pipe = pipes.create((Open) msg);
      dispatcher.dispatch(serviceId(), pipe, logId).whenComplete((result, ex) -> {
        if (ex == null) {
          try {
            post(Close.withSuccess(pipe.id(), codec.nativeToTransferable(result)));
          } catch (Exception ex2) {
            logger.error("failed to post the result: {}", result);
            post(Close.withError(pipe.id(), Abort.Unexpected, ex2.getMessage()));
          }
        } else {
          int code = Abort.Unexpected;
          if (ex instanceof NoSuchServiceException) {
            code = Abort.ServiceUndefined;
          } else if (ex instanceof NoSuchFunctionException) {
            code = Abort.FunctionUndefined;
          }
          post(Close.withError(pipe.id(), code, ex.getMessage()));
        }
      });
      return;
    }

    // メッセージの配信先パイプを参照
    Optional<Pipe> optPipe = pipes.get(msg.pipeId);
    if (!optPipe.isPresent()) {
      if (msg instanceof Close) {
        logger.debug("{}: both of sessions unknown pipe #{}", logId, msg.pipeId & 0xFFFF);
      } else if (msg instanceof Block) {
        logger.debug("{}: unknown pipe-id: {}", logId, msg);
        post(Close.withError(msg.pipeId, String.format("unknown pipe-id specified: #%04X", msg.pipeId & 0xFFFF)));
      } else {
        logger.warn("{}: unexpected message was delivered: {}", logId, msg);
      }
      return;
    }

    Pipe pipe = optPipe.get();

    try {
      if (msg instanceof Block) {
        // Open は受信したがサービスの処理が完了していない状態でメッセージを受信した場合にパイプのキューに保存できるよう
        // 一度パイプを経由して deliver(Pipe,Message) を実行する
        if (pipe instanceof StreamPipe) {
          ((StreamPipe) pipe).dispatchBlock((Block) msg);
        } else {
          // ブロックの受信が宣言されていないパイプに対してはエラーでクローズする
          logger.warn("{}: block reception is not permitted on this pipe: {}, closing", logId, pipe);
          pipe.closeWithError(Abort.FunctionCannotReceiveBlock,
              "function %d does not allow block transmission", pipe.functionId());
        }
      } else if (msg instanceof Close) {
        pipe.closePassively((Close) msg);
      } else {
        throw new IllegalStateException("unexpected message: " + msg);
      }
    } catch (Throwable ex) {
      logger.error("{}: unexpected error: {}, wsClosed pipe {}", logId, msg, pipe, ex);
      post(Close.withError(msg.pipeId, "internal error"));
      if (ex instanceof ThreadDeath) {
        throw (ThreadDeath) ex;
      }
    }
  }

  // ==============================================================================================
  // メッセージの送信
  // ==============================================================================================

  /**
   * ピアに対して指定されたメッセージを送信します。
   */
  private void post(Message msg) {
    if (closed() && !(msg instanceof Control)) {
      logger.error("{}: session {} has been closed, message is discarded: {}", logId, id, msg);
    } else {
      try {
        outboundLatch.exec(() -> wire.outbound.offer(msg));
      } catch (InterruptedException ex) {
        logger.error("{}: operation interrupted", logId, ex);
      }
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
        logger.debug(logId + ": normal method: " + Debug.getSimpleName(method));
        return method.invoke(this, args);
      } else {
        // there is no way to receive block in interface binding
        logger.debug(logId + ": calling remote method: " + Debug.getSimpleName(method));
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

  Pipe.Stub stub = new Pipe.Stub() {
    @Nonnull
    @Override
    public String id() {
      return Session.this.id().toString();
    }

    @Override
    public void post(@Nonnull Message msg) {
      Session.this.post(msg);
    }

    @Override
    public void closed(@Nonnull Pipe pipe) {
      pipes.destroy(pipe.id());
    }
  };

  public interface Listener {
    void sessionClosed(@Nonnull Session session);
  }

}
