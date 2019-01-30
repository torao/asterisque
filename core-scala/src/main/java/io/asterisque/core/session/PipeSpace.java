package io.asterisque.core.session;

import io.asterisque.Asterisque;
import io.asterisque.core.ProtocolException;
import io.asterisque.core.msg.Abort;
import io.asterisque.core.msg.Open;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pipe の生成と消滅を管理するクラス。セッションからパイプの管理に関する部分を切り離した。
 */
class PipeSpace {
  private static final Logger logger = LoggerFactory.getLogger(PipeSpace.class);

  /**
   * 新規のパイプ ID を発行するためのシーケンス番号。
   */
  private final AtomicInteger sequence = new AtomicInteger(0);

  /**
   * 現在アクティブな Pipe のマップ。
   */
  private final ConcurrentHashMap<Short, Pipe> pipes = new ConcurrentHashMap<>();

  /**
   * クローズされているかの判定。
   */
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private final Session session;
  private final short pipeMask;

  PipeSpace(Session session) {
    this.session = session;
    this.pipeMask = session.isPrimary() ? Pipe.PrimaryMask : 0;
  }

  /**
   * このパイプ空間から指定された ID のパイプを参照します。ID に該当するパイプが存在しない場合は empty() を返します。
   *
   * @param pipeId 参照するパイプの ID
   * @return パイプ
   */
  @Nonnull
  public Optional<Pipe> get(short pipeId) {
    return Optional.ofNullable(pipes.get(pipeId));
  }

  /**
   * ピアから受信した Open メッセージに対応するパイプを構築します。要求されたパイプ ID が既に使用されている場合は
   * Optional.empty() を返します。
   *
   * @param open Open メッセージ
   * @throws ProtocolException 指定されたパイプ ID がすでに利用されている場合
   */
  @Nonnull
  public Pipe create(@Nonnull Open open) throws ProtocolException {
    if (!closed.get()) {
      throw new IllegalStateException("session has already been closed");
    }

    // 要求されたパイプ ID がプロトコルに従っていることを確認
    boolean hasPrimaryMask = (open.pipeId & Pipe.PrimaryMask) != 0;
    if (session.isPrimary() == hasPrimaryMask) {
      String format;
      if (session.isPrimary()) {
        format = "pipe-id with primary mask %04X isn't accepted from non-primary peer";
      } else {
        format = "pipe-id without primary mask %04X isn't acceptable from primary peer";
      }
      throw new ProtocolException(String.format(format, open.pipeId & 0xFFFF));
    }

    // 新しいパイプを構築して登録
    Pipe pipe = new Pipe(session.codec(), open, session.stub);
    Pipe old = pipes.putIfAbsent(open.pipeId, pipe);
    if (old != null) {
      // 既に使用されているパイプ ID が指定された場合はエラー
      String msg = String.format("duplicate pipe-id specified: %d; %s", open.pipeId, old);
      logger.error(msg);
      throw new ProtocolException(msg);
    }
    return pipe;
  }

  /**
   * ピアに対して Open メッセージを送信するためのパイプを生成します。
   *
   * @param priority プライオリティ
   * @param function ファンクション ID
   */
  @Nonnull
  public Pipe create(byte priority, short function) {
    while (true) {
      if (!closed.get()) {
        throw new IllegalStateException("session has already been closed");
      }
      short id = (short) ((sequence.getAndIncrement() & 0x7FFF) | pipeMask);
      Open open = new Open(id, priority, function, Asterisque.Empty.Objests);
      Pipe pipe = new Pipe(session.codec(), open, session.stub);
      if (pipes.putIfAbsent(id, pipe) == null) {
        return pipe;
      }
    }
  }

  /**
   * 指定されたパイプ ID のパイプを削除します。
   *
   * @param pipeId パイプ空間から削除するパイプの ID
   */
  public void destroy(short pipeId) {
    pipes.remove(pipeId);
  }

  /**
   * このパイプ空間が保持している全てのパイプを破棄します。graceful に true を指定した場合はパイプに対して Close(Abort)
   * メッセージを送信します。
   *
   * @param graceful Close メッセージ配信を行わず強制的に終了する場合 false
   */
  public void close(boolean graceful) {
    if (closed.compareAndSet(false, true)) {
      if (graceful) {
        // 残っているすべてのパイプに Close メッセージを送信
        pipes.values().forEach(pipe -> {
          String msg = String.format("session %s is closing", session.id());
          pipe.closeWithError(Abort.SessionClosing, msg);
        });
      }
      pipes.clear();
    }
  }

}
