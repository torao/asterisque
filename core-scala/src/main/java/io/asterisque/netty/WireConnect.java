/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.ssl.SslHandler;
import io.asterisque.Asterisque;
import io.asterisque.core.Debug;
import io.asterisque.Node;
import io.asterisque.Options;
import io.asterisque.core.msg.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// WireConnect
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class WireConnect extends SimpleChannelInboundHandler<Message> {
  private static Logger logger = LoggerFactory.getLogger(WireConnect.class);

  /**
   * WireConnect の ID 生成のためのシーケンス番号。ログ出力のための情報であるため循環によって重複が発生しても良い。
   */
  private static final AtomicInteger Sequence = new AtomicInteger(0);

  private final Node node;
  private final SocketAddress local;
  private final SocketAddress remote;
  private final Options options;
  private final Optional<SslHandler> sslHandler;
  private final boolean isServer;
  private final Consumer<NettyWire> onWireCreate;
  private final String sym;

  /**
   * この接続の Wire。
   */
  private volatile Optional<NettyWire> wire = Optional.empty();

  /**
   * ログ上で Wire の動作を識別するための ID 番号。
   */
  private final int id = Sequence.getAndIncrement() & 0x7FFFFFFF;

  // ==============================================================================================
  // コンストラクタ
  // ==============================================================================================
  /**
   */
  public WireConnect(Node node, SocketAddress local, SocketAddress remote, boolean isServer,
                     Optional<SslHandler> sslHandler, Consumer<NettyWire> onWireCreate, Options options){
    super(Message.class);
    this.node = node;
    this.local = local;
    this.remote = remote;
    this.sslHandler = sslHandler;
    this.isServer = isServer;
    this.onWireCreate = onWireCreate;
    this.options = options;
    this.sym = isServer? "S": "C";
  }

  // ==============================================================================================
  // チャネルの接続
  // ==============================================================================================
  /**
   * 接続が完了したときに呼び出されます。
   * SSL ハンドシェイクの完了処理を実装します。
   * @param ctx コンテキスト
   */
  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    trace("channelActive(" + ctx.name() + ")");
    assert(! wire.isPresent());

    // SSLHandler が指定されている場合はハンドシェイク完了後に SSLSession を取得
    CompletableFuture<Optional<SSLSession>> future = new CompletableFuture<>();
    if(sslHandler.isPresent()) {
      SslHandler h = sslHandler.get();
      h.handshakeFuture().addListener(f -> {
        SSLSession session = h.engine().getSession();
        if(session.isValid()) {
          // SSL ハンドシェイク完了
          future.complete(Optional.of(session));
          debug("tls handshake success");
        } else {
          // SSL ハンドシェイク失敗
          future.completeExceptionally(new IOException("tls handshake failure: invalid session"));
          debug("tls handshake failure: invalid session");
        }
        Debug.dumpSSLSession(logger, sym + "[" + id + "]", session);
      });
    } else {
      // SSL なし
      future.complete(Optional.empty());
    }

    // Wire 構築
    NettyWire w = new NettyWire(node, local, remote, isServer, future, ctx);
    wire = Optional.of(w);

    super.channelActive(ctx);

    // 接続完了を通知
    onWireCreate.accept(w);
  }

  // ==============================================================================================
  // チャネルの切断
  // ==============================================================================================
  /**
   * @param ctx コンテキスト
   */
  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    trace("channelInactive(" + ctx.name() + ")");
    closeWire();
    super.channelInactive(ctx);
  }

  // ==============================================================================================
  // メッセージの受信
  // ==============================================================================================
  /**
   * @param ctx コンテキスト
   * @param msg メッセージ
   */
  @Override
  public void channelRead0(ChannelHandlerContext ctx, Message msg) throws Exception {
    trace("channelRead0(" + ctx.name() + "," + msg + ")");

    // メッセージを通知
    assert(wire.isPresent());
    wire.ifPresent( w -> {
      w.receive(msg);
    });

    // super.channelRead0(ctx, msg) スーパークラスは未実装
  }

  // ==============================================================================================
  // 例外の発生
  // ==============================================================================================
  /**
   * @param ctx コンテキスト
   * @param cause 発生した例外
   */
  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause){
    logger.debug(id() + ": wsCaughtException caught", cause);
    closeWire();
  }

  // ==============================================================================================
  // ワイヤーのクローズ
  // ==============================================================================================
  /**
   */
  private void closeWire() {
    trace("closeWire()");
    if(wire.isPresent()){
      wire.get().close();
      wire = Optional.empty();
    }
  }

  private void debug(String log) {
    if(logger.isDebugEnabled()) {
      logger.debug(id() + ": " + log);
    }
  }

  private void trace(String log){
    if(logger.isTraceEnabled()){
      logger.trace(id() + ": " + log);
    }
  }

  public String id() {
    return wire.map(NettyWire::id).orElse(Asterisque.logPrefix(isServer));
  }

}
