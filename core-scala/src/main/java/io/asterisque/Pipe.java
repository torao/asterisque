/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
 */
package io.asterisque;

import io.asterisque.core.Debug;
import io.asterisque.core.msg.Abort;
import io.asterisque.core.msg.Block;
import io.asterisque.core.msg.Close;
import io.asterisque.core.msg.Open;
import io.asterisque.util.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Pipe
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

/**
 * function に対する呼び出し状態を表すクラスです。function の呼び出し開始から終了までのスコープを持ち、その呼び
 * 出し結果は `pipe.future` を使用して非同期で参照することができます。またパイプは非同期メッセージングにおける
 * メッセージの送出先/流入元を表しており同期ストリーミングもパイプを経由して行います。
 * <p>
 * パイプの ID は `Session` を開始した側 (クライアント側) が最上位ビット 0、相手からの要求を受け取った側 (サー
 * ビス側) が 1 を持ちます。このルールは通信の双方で相手側との合意手続きなしに重複しないユニークなパイプ ID を
 * 発行することを目的としています。このため一つのセッションで同時に行う事が出来る呼び出しは最大で 32,768、パイプを
 * 共有しているピアの双方で 65,536 個までとなります。
 *
 * @author Takami Torao
 */
public final class Pipe {
  private static final Logger logger = LoggerFactory.getLogger(Pipe.class);

  /**
   * {@link Session#isServer} が true の通信端点側で新しいパイプ ID を発行するときに立てるビットフラグです。
   */
  public static final short UniqueMask = (short) (1 << 15);

  public final short id;
  public final short function;
  public final byte priority;
  public final Session session;

  /**
   * このパイプがクローズされているかどうかを表すアトミックなフラグ。
   */
  private AtomicBoolean closed = new AtomicBoolean(false);

  /**
   * このパイプにブロックが到着した時に呼び出すイベントハンドラ。
   * アプリケーションはこのハンドラではなく [[src]] を使用する。
   */
  private EventHandlers<Block> onBlock = new EventHandlers<>();

  /**
   * このパイプがクローズされたときに呼び出すイベントハンドラ。
   * アプリケーションはこのハンドラではなく [[future]] を使用する。
   */
  private EventHandlers<Boolean> onClosing = new EventHandlers<>();

  /**
   * このパイプがクローズされて確定した結果を通知するための `Promise`。
   * このパイプによる双方の処理で確定した結果 (成功/失敗にかかわらず) を参照するための `Future` です。
   * パイプの結果が確定した時点でパイプはクローズされています。
   */
  public final CompletableFuture<Object> future = new CompletableFuture<>();

  /**
   * 非同期メッセージングの Block メッセージ受信を行うメッセージソース。
   * このパイプによる非同期メッセージングの受信処理を設定する非同期コレクションです。
   * この非同期コレクションは処理の呼び出しスレッド内でしか受信処理を設定することが出来ません。
   */
  public final Source<Block> src = new PipeMessageSource(this);

  /**
   * このパイプに対する非同期メッセージングの送信を行うメッセージシンクです。
   * クローズされていないパイプに対してであればどのようなスレッドからでもメッセージの送信を行うことが出来ます。
   */
  public final PipeMessageSink sink = new PipeMessageSink(this);

  /**
   * 受信したブロックを同期で読み込むためのキューは PipeInputStream に存在する。
   * private final BlockingQueue<Block> blocks = new LinkedBlockingQueue<>(0);
   */

  private final PipeOutputStream out;

  /**
   * @param id       パイプ ID
   * @param priority このパイプで発生するメッセージのプライオリティ
   * @param function このパイプの呼び出し先 function 番号
   * @param session  このパイプのセッション
   */
  Pipe(short id, byte priority, short function, Session session) {
    this.id = id;
    this.function = function;
    this.priority = priority;
    this.session = session;

    this.out = new PipeOutputStream(this, session.writeBarrier);

    logger.trace(this + ": pipe " + id + " for function " + function + " with priority " + priority + " created");
    // ブロックを受信したらメッセージソースに通知
    onBlock.add((PipeMessageSource) src);
  }

  // ==============================================================================================
  // ブロックの受信
  // ==============================================================================================

  /**
   * このパイプに指定されたブロックが到着した時に呼び出されます。
   *
   * @param block 受信したブロック
   */
  void dispatchBlock(Block block) {
    onBlock.accept(block);
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
    Open open = new Open(id, priority, function, params);
    session.post(priority, open);
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
    block(new Block(id, buffer, offset, length));
  }

  // ==============================================================================================
  // ブロックの受信
  // ==============================================================================================

  /**
   * 指定された Block メッセージを非同期メッセージングのメッセージとして送信します。
   *
   * @see PipeMessageSink
   */
  void block(Block block) {
    if (logger.isTraceEnabled()) {
      logger.trace(this + ": sending block: " + block);
    }
    session.post(priority, block);
  }

  // ==============================================================================================
  // パイプのクローズ
  // ==============================================================================================

  /**
   * 指定された結果で Close メッセージを送信しパイプを閉じます。
   * このメソッドは正常に結果が確定したときの動作です。
   *
   * @param result Close に付加する結果
   */
  void close(Object result) {
    if (closed.compareAndSet(false, true)) {
      onClosing.accept(true);
      session.post(priority, new Close(id, result));
      future.complete(result);
      session.destroy(id);
      logger.trace(this + ": pipe is closed with success: " + Debug.toString(result));
    } else {
      logger.debug(this + ": pipe already closed: " + Debug.toString(result));
    }
  }

  // ==============================================================================================
  // パイプのクローズ
  // ==============================================================================================

  /**
   * 指定した例外で Close を送信しパイプを閉じます。
   * このメソッドはエラーが発生したときの動作です。
   *
   * @param ex Close に付加する例外
   */
  void close(Throwable ex, String msg) {
    if (closed.compareAndSet(false, true)) {
      onClosing.accept(true);
      session.post(priority, new Close(id, new Abort(Abort.Unexpected, msg)));
      future.completeExceptionally(ex);
      session.destroy(id);
      logger.trace(this + ": pipe is closed with failure: " + ex);
    } else {
      logger.debug(this + ": pipe already closed: " + ex);
    }
  }

  // ==============================================================================================
  // パイプのクローズ
  // ==============================================================================================

  /**
   * 相手側から受信した Close メッセージによってこのパイプを閉じます。
   */
  void close(Close close) {
    if (closed.compareAndSet(false, true)) {
      onClosing.accept(false);
      if (close.abort != null) {
        logger.trace(this + ": close(" + close + "): aborted: " + close.abort);
        future.completeExceptionally(close.abort);
      } else {
        logger.trace(this + ": close(" + close + "): success: " + Debug.toString(close.result));
        future.complete(close.result);
      }
      session.destroy(id);
      logger.trace(this + ": pipe is closed by peer: " + close);
    } else {
      logger.debug(this + ": pipe already closed: " + close);
    }
  }

  // ==============================================================================================
  // 出力ストリーム
  // ==============================================================================================

  /**
   * 指定された {@link java.io.OutputStream} を使用してストリーム形式のブロック出力を行います。
   * 出力データが内部的なバッファの上限に達するか {@code flush()} が実行されるとバッファの内容が {@link Block}
   * として非同期で送信されます。
   * ストリームはマルチスレッドの出力に対応していません。
   */
  public OutputStream out() {
    return out;
  }

  /**
   * このパイプが受信したブロックを `InputStream` として参照します。
   *
   * @@[[com.kazzla.asterisk.Export]](stream=true) 宣言されているメソッドのパイプのみ使用することが
   * 出来ます。
   */
  private final AtomicReference<PipeInputStream> in = new AtomicReference<>(null);

  /**
   * このパイプがメッセージとして受信したバイナリデータを `InputStream` として参照します。
   * 非同期で到着するメッセージを同期ストリームで参照するために、入力ストリームを使用する前に [[useInputStream()]]
   * を呼び出して内部的なキューを準備しておく必要があります。[[useInputStream()]] を行わず入力ストリームを参照
   * した場合は例外となります。
   */
  public InputStream in() throws IOException {
    if (in.get() == null) {
      throw new IOException(this + ": useInputStream() is not declared on pipe");
    } else {
      return in.get();
    }
  }

  /**
   * 非同期で到着するバイナリーデータを [[in]] を使用した同期ストリームとして参照するためのキューを準備します。
   * このメソッドを呼び出すと受信したブロックのバッファリグが行われますので、入力ストリームを使用する場合のみ
   * 呼び出してください。
   */
  public void useInputStream() {
    assertInCall("useInputStream() must be call in caller thread, Ex. session.open(10){_.useInputStream()}, 10.accept{withPipe{pipe=>pipe.useInputStream();...}}");
    PipeInputStream in = new PipeInputStream(session.readBreaker, Integer.MAX_VALUE /* TODO Pipeあたりの最大サイズ */, this.toString());
    src.foreach(in);
    this.in.set(in);
    onClosing.add(me -> {
      if (me) {
        in.close();
      } else if (!in.isClosed()) {
        in.accept(Block.eof(id));
      }
    });
    logger.trace(this + ": prepare internal buffer for messaging that is used for InputStream");
  }

  /**
   * @return このインスタンスの文字列
   */
  @Override
  public String toString() {
    return session.logId() + "#" + (id & 0xFFFF);
  }

  /**
   * スレッドに結びつけられたパイプを参照するためのスレッドローカル。
   */
  private static final ThreadLocal<Pipe> pipes = new ThreadLocal<>();

  // ==============================================================================================

  /**
   * 呼び出し元のスレッドに関連づけられている Pipe を使用してラムダ {@code exec} を実行します。
   * スレッドが Pipe と関連づけられていない場合は exec の呼び出しは行われず def の実行結果が返されます。
   *
   * @param def  デフォルト値
   * @param exec パイプを使用する処理
   * @return 処理結果
   */
  public static <T> T orElse(Supplier<T> def, Function<Pipe, T> exec) {
    Optional<Pipe> p = currentPipe();
    if (p.isPresent()) {
      return exec.apply(p.get());
    } else {
      return def.get();
    }
  }

  /**
   * 現在のスレッドに結び付けられているパイプを参照します。
   */
  private static Optional<Pipe> currentPipe() {
    if (pipes.get() == null) {
      return Optional.empty();
    } else {
      return Optional.of(pipes.get());
    }
  }

  /**
   * 現在のスレッドにパイプが関連づけられていない場合に指定されたメッセージ付きの例外を発生します。
   *
   * @param msg 例外メッセージ
   */
  static void assertInCall(String msg) {
    if (pipes.get() == null) {
      throw new IllegalStateException(msg);
    }
  }

  static void using(Pipe pipe, Runnable exec) {
    using(pipe, () -> {
      exec.run();
      return 0;
    });
  }

  static <T> T using(Pipe pipe, Supplier<T> exec) {
    Pipe old = pipes.get();
    pipes.set(pipe);
    try {
      return exec.get();
    } finally {
      pipes.set(old);
    }
  }

}
