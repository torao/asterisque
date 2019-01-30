package io.asterisque.core.session;

import io.asterisque.Asterisque;
import io.asterisque.core.Debug;
import io.asterisque.core.codec.VariableCodec;
import io.asterisque.core.msg.Control;
import io.asterisque.core.msg.Message;
import io.asterisque.core.msg.SyncSession;
import io.asterisque.core.wire.MessageQueue;
import io.asterisque.core.wire.Wire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

public class Dispatcher {
  private static final Logger logger = LoggerFactory.getLogger(Dispatcher.class);

  /**
   * このディスパッチャーのノード ID
   */
  private final UUID nodeId;

  private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

  private final Executor executor;

  private final VariableCodec codec;

  public Dispatcher(@Nonnull UUID nodeId, @Nonnull Executor executor, @Nonnull VariableCodec codec) {
    this.nodeId = nodeId;
    this.executor = executor;
    this.codec = codec;
  }

  /**
   * このディスパッチャー上で提供するサービス。
   */
  public final Services services = new Services() {
    private final ConcurrentHashMap<String, Service> services = new ConcurrentHashMap<>();

    @Override
    @Nullable
    public Service set(@Nonnull String serviceId, @Nonnull Service service) {
      return services.put(serviceId, service);
    }

    @Override
    @Nullable
    public Service remove(@Nonnull String serviceId) {
      return services.remove(serviceId);
    }

    @Override
    @Nullable
    public Service get(@Nonnull String serviceId) {
      return services.get(serviceId);
    }

  };

  public CompletableFuture<Session> bind(@Nonnull Wire wire, @Nonnull String serviceId, int ping, int sessionTimeout) {

    // セッション ID の決定
    UUID sessionId = Asterisque.Zero;
    if (wire.isPrimary()) {
      do {
        sessionId = UUID.randomUUID();
      } while (sessions.containsKey(sessionId));
      logger.trace("new session-id issued: {}", sessionId);
    }

    // ハンドシェイクの開始
    Handshake handshake = new Handshake(wire, sessionId, serviceId, ping, sessionTimeout);
    return handshake.future;
  }

  @Nonnull
  private Session handshake(@Nonnull Wire wire, @Nonnull SyncSession sent, @Nonnull SyncSession received) {
    String logPrifix = Asterisque.logPrefix(wire.isPrimary(), sent.sessionId);

    // TODO クライアント証明が必要な場合はこの位置で相手の SYNC_SESSION と wire の証明書を検証
    wire.session().ifPresent(tls -> {
      logger.debug("{}: verifying ssl certificate: {}", logPrifix, tls);
      if(logger.isTraceEnabled()){
        try {
          Debug.dumpSSLSession(logger, logPrifix, tls);
        } catch(SSLException ex){
          logger.warn("{}: cannot verify ssl certificate: {}", logPrifix, tls);
        }
      }
      // TODO 証明書の検証が正しいことを確認
      // TODO 証明書の検証を行うかや、ローカルインストール済みの CA 証明書なども検証
      if(! tls.isValid() || !Arrays.equals(tls.getId(), received.nodeId.toString().getBytes())){
        throw new ProtocolViolationException("invalid ssl certificate");
      }
    });

    SyncSession.Pair sync = wire.isPrimary() ? new SyncSession.Pair(sent, received) : new SyncSession.Pair(received, sent);
    UUID sessionId = sync.sessionId();
    AtomicReference<Session> ref = new AtomicReference<>();
    sessions.compute(sessionId, (ignored, oldSession) -> {
      if (sessionId.equals(Asterisque.Zero) || oldSession != null) {
        logger.debug("{}: session id collision: {}", logPrifix, sessionId);
        wire.outbound.offer(new Control(Control.Close));
        wire.close();
        return oldSession;
      }
      Session session = new Session(sessionId, this, wire, codec, sync);
      session.addListener(session1 -> {
        logger.debug("{}: session closed: {}", logPrifix, sessionId);
        sessions.remove(sessionId);
      });
      ref.set(session);
      return ref.get();
    });
    logger.info("{}: handshake success, beginning session", logPrifix);
    return ref.get();
  }

  CompletableFuture<Object> dispatch(String serviceId, @Nonnull Pipe pipe, @Nonnull String logId) {
    Service service = services.get(serviceId);
    if (service == null) {
      CompletableFuture<Object> future = new CompletableFuture<>();
      String msg = String.format("no such service: %s", serviceId);
      logger.debug("{}: {}", logId, msg);
      future.completeExceptionally(new NoSuchServiceException(msg));
      return future;
    }
    return service.apply(pipe, executor);
  }

  public interface Services {
    @Nullable
    Service set(@Nonnull String serviceId, @Nonnull Service service);

    @Nullable
    Service remove(@Nonnull String services);

    @Nullable
    Service get(@Nonnull String serviceId);
  }

  private class Handshake implements MessageQueue.Listener {
    public final CompletableFuture<Session> future;
    private final Wire wire;
    private final SyncSession sent;
    private boolean polled = false;

    private Handshake(Wire wire, UUID sessionId, String serviceId, int ping, int sessionTimeout) {
      this.future = new CompletableFuture<>();
      this.wire = wire;

      // ハンドシェイクメッセージの送信
      this.sent = new SyncSession(nodeId, sessionId, serviceId, System.currentTimeMillis(), ping, sessionTimeout);
      wire.outbound.offer(sent.toControl());

      // ハンドシェイクメッセージの受信ハンドラを設置
      wire.inbound.addListener(this);
    }

    @Override
    public void messagePollable(@Nonnull MessageQueue messageQueue, boolean pollable) {

      // 受信メッセージの取得
      Message msg = pollable && !polled ? messageQueue.poll() : null;
      if (msg == null) {
        return;
      }
      polled = true;

      try {
        if (msg instanceof Control) {
          future.complete(handshake(wire, sent, SyncSession.parse((Control) msg)));
        } else {
          String message = String.format("SyncSession control message expected but a different one detected: %s", msg);
          logger.error(message);
          throw new ProtocolViolationException(message);
        }
      } catch (Exception ex) {
        logger.error(ex.toString());
        future.completeExceptionally(ex);
        wire.close();
      } finally {
        messageQueue.removeListener(this);
        // TODO ハンドシェイクタイムアウト設定
      }
    }
  }

}
