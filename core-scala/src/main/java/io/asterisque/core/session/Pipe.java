package io.asterisque.core.session;

import io.asterisque.PipeMessageSink;
import io.asterisque.core.Debug;
import io.asterisque.core.msg.Block;
import io.asterisque.core.msg.Close;
import io.asterisque.core.msg.Message;
import io.asterisque.core.msg.Open;
import io.asterisque.core.wire.Wire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * function に対する呼び出し状態を表すクラスです。function の呼び出し開始から終了までのスコープを持ち、その呼び
 * 出し結果は `pipe.future` を使用して非同期で参照することができます。またパイプは非同期メッセージングにおける
 * メッセージの送出先/流入元を表しており同期ストリーミングもパイプを経由して行います。
 * <p>
 * パイプの ID は `Session` を開始した側 (クライアント側) が最上位ビット 0、相手からの要求を受け取った側 (サー
 * ビス側) が 1 を持ちます。このルールは通信の双方で相手側との合意手続きなしに重複しないユニークなパイプ ID を
 * 発行することを目的としています。このため一つのセッションで同時に行う事が出来る呼び出しは最大で 32,768、パイプを
 * 共有しているピアの双方で 65,536 個までとなります。
 */
public class Pipe {
  private static final Logger logger = LoggerFactory.getLogger(Pipe.class);

  /**
   * {@link Wire#isPrimary()}} が true の通信端点側で新しいパイプ ID を発行するときに立てるビットフラグです。
   */
  public static final short PrimaryMask = (short) (1 << (Short.SIZE - 1));

  private final Open open;

  /**
   * このパイプがクローズされているかどうかを表すフラグ。
   */
  private final AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * パイプの処理結果。
   */
  private final CompletableFuture<Object> future = new CompletableFuture<>();

  /**
   * このパイプによる双方の処理で確定した結果 (成功/失敗にかかわらず) を参照するための Future です。結果が確定した時点で
   * パイプはクローズされています。
   */
  @Nonnull
  public CompletableFuture<Object> future() {
    return future;
  }

  /**
   * 受信したブロックを同期で読み込むためのキューは PipeInputStream に存在する。
   * private final BlockingQueue<Block> blocks = new LinkedBlockingQueue<>(0);
   */

  // private final PipeOutputStream out;

  private final Stub stub;

  /**
   * @param open オープンメッセージ
   * @param stub スタブ
   */
  Pipe(@Nonnull Open open, @Nonnull Stub stub) {
    this.open = open;
    this.stub = stub;
    logger.trace("{}: pipe {} for function {} with priority {} created", this, id(), functionId(), priority());
  }

  /**
   * このパイプの ID を参照します。
   *
   * @return パイプ ID
   */
  public short id() {
    return open.pipeId;
  }

  /**
   * このパイプのプライオリティを参照します。
   *
   * @return プライオリティ
   */
  public byte priority() {
    return open.priority;
  }

  /**
   * ファンクション ID を参照します。
   *
   * @return ファンクション ID
   */
  public short functionId() {
    return open.functionId;
  }

  // ==============================================================================================
  //　クローズ判定
  // ==============================================================================================

  /**
   * このパイプがクローズされているときに true を返します。
   */
  public boolean closed() {
    return closed.get();
  }

  // ==============================================================================================
  // Open メッセージの送信
  // ==============================================================================================

  /**
   * このパイプが示す function 番号に対して指定された引数で Open メッセージを送信します。
   *
   * @param params function 呼び出し時の引数
   */
  void open(Object[] params) {
    logger.trace(this + ": sending open");
    Open open = new Open(id(), priority(), functionId(), params);
    stub.post(open);
  }

  // ==============================================================================================
  // ブロックの送信
  // ==============================================================================================

  /**
   * 指定されたバイナリデータを非同期メッセージングのメッセージとして Block を送信します。
   *
   * @see PipeMessageSink
   */
  void block(byte[] buffer, int offset, int length) {
    block(new Block(id(), buffer, offset, length));
  }

  /**
   * 指定された Block メッセージを非同期メッセージングのメッセージとして送信します。
   *
   * @see PipeMessageSink
   */
  void block(Block block) {
    if (logger.isTraceEnabled()) {
      logger.trace("{}: sending block: {}", this, block);
    }
    stub.post(block);
  }

  // ==============================================================================================
  // パイプのクローズ
  // ==============================================================================================

  /**
   * 指定された結果で Close メッセージを送信しパイプを閉じます。このメソッドは正常に結果が確定したときの動作です。
   *
   * @param result Close に付加する結果
   */
  void close(Object result) {
    close(new Close(id(), result));
  }

  /**
   * エラーメッセージ付きの Close を送信しパイプを閉じます。このメソッドはエラーが発生したときの動作です。
   *
   * @param msg エラーメッセージ
   */
  void closeWithError(String msg) {
    close(Close.unexpectedError(id(), msg));
  }

  /**
   * 指定された Close メッセージでこのパイプと peer のパイプをクローズします。
   *
   * @param close Close メッセージ
   */
  private void close(@Nonnull Close close) {
    if (closed.compareAndSet(false, true)) {
      stub.post(close);
      if (close.abort == null) {
        future.complete(close.result);
        logger.trace("{}: pipe was closed successfully: {}", this, Debug.toString(close.result));
      } else {
        future.completeExceptionally(close.abort);
        logger.trace("{}: pipe was closed with failure: {}", this, close.abort);
      }
      stub.closed(this);
    } else {
      logger.debug("{}: pipe has already been closed: {}", this, close);
    }
  }


  /**
   * peer から受信した {@code Close} メッセージによってこのパイプを閉じます。
   *
   * @param close 受信した Close メッセージ
   */
  void closePassively(Close close) {
    if (closed.compareAndSet(false, true)) {
      if (close.abort != null) {
        logger.trace("{}: close({}): aborted: {}", this, close, close.abort);
        future.completeExceptionally(close.abort);
      } else {
        logger.trace("{}: close({}): success: {}", this, close, Debug.toString(close.result));
        future.complete(close.result);
      }
      stub.closed(this);
      logger.trace("{}: pipe is closed by peer: {}", this, close);
    } else {
      logger.trace("{}: pipe is already been closed: {}", this, close);
    }
  }

  // ==============================================================================================
  // 出力ストリーム
  // ==============================================================================================

  /**
   * このパイプ上で {@link Block} を使用してバイナリデータを送信する {@link OutputStream} を参照します。
   * 出力データが内部的なバッファの上限に達するか {@code flush()} が実行されるとバッファの内容が {@link Block}
   * として非同期で送信されます。
   * ストリームはマルチスレッドの出力に対応していません。
   */
  public OutputStream out() {
    return new OutputStream() {
      private final byte[] oneByte = new byte[1];

      @Override
      public void write(int b) throws IOException {
        oneByte[0] = (byte) b;
        write(oneByte);
      }

      @Override
      public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
      }

      @Override
      public void write(byte[] b, int o, int l) throws IOException {
        if (closed()) {
          throw new IOException("pipe is already been closed");
        }
        block(b, o, l);
      }
    };
  }

  /**
   * @return このインスタンスの文字列
   */
  @Override
  @Nonnull
  public String toString() {
    return String.format("%s#%04X", stub.id(), id() & 0xFFFF);
  }

  interface Stub {
    @Nonnull
    String id();

    void post(@Nonnull Message msg);

    void closed(@Nonnull Pipe pipe);
  }

}
