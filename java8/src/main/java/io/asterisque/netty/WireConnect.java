/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.netty;

import io.asterisque.Debug;
import io.asterisque.Message;
import io.asterisque.Wire;
import io.asterisque.conf.Config;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
	private final AtomicInteger Sequence = new AtomicInteger(0);

	private final Config config;
	private final Optional<SslHandler> sslHandler;
	private final boolean isServer;
	private final Consumer<NettyWire> onWireCreate;

	/*
	private final int sendAdvisoryLimit;
	private final int sendBlockingLimit;
	private final Consumer<Boolean> sendBackPressure;
	private final int recvAdvisoryLimit;
	*/
	private final Consumer<Message> dispatcher;
	private final Consumer<Wire> disposer;
	private final Executor executor;
	private final String sym;

	/**
	 * この接続の Wire。
	 */
	private volatile Optional<NettyWire> wire = Optional.empty();

	/**
	 * ログ上で Wire の動作を識別するための ID 番号。
	 */
	private final int id = Sequence.getAndIncrement() & Integer.MAX_VALUE;

	/**
	 * コンストラクタ
	 * @param sslHandler
	 * @param isServer
	 * @param onWireCreate
	 * @param dispatcher
	 * @param executor
	 */
	public WireConnect(Optional<SslHandler> sslHandler, boolean isServer, Consumer<NettyWire> onWireCreate, Executor executor, Consumer<Message> dispatcher, Consumer<Wire> disposer, Config config){
		this.sslHandler = sslHandler;
		this.isServer = isServer;
		this.onWireCreate = onWireCreate;
		this.config = config;
		this.dispatcher = dispatcher;
		this.disposer = disposer;
		this.executor = executor;
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
		CompletableFuture<Optional<SSLSession>> future = new CompletableFuture<>();
		if(sslHandler.isPresent()) {
			SslHandler h = sslHandler.get();
			h.handshakeFuture().addListener(f -> {
				SSLSession session = h.engine().getSession();
				if(session.isValid()) {
					future.complete(Optional.of(session));
					debug("tls handshake success");
				} else {
					future.completeExceptionally(new IOException("tls handshake failure: invalid session"));
					debug("tls handshake failure: invalid session");
				}
				Debug.dumpSSLSession(logger, sym + "[" + id + "]", session);
			});
		} else {
			future.complete(Optional.empty());
		}
		/* 	public NettyWire(CompletableFuture<Optional<SSLSession>> tls, boolean server, ConnectConfig config,
		ChannelHandlerContext context,
		Consumer<Message> dispatcher, Executor executor){
		*/
		NettyWire w = new NettyWire(future, isServer, config, ctx, dispatcher, disposer, executor);
		/*
		NettyWire w = new NettyWire(
			ctx.channel().remoteAddress(), isServer, future, ctx,
			sendAdvisoryLimit, sendBlockingLimit, sendBackPressure,
			recvAdvisoryLimit, Integer.MAX_VALUE, pressure -> ctx.channel().config().setAutoRead(! pressure),
			dispatcher, executor);
		*/
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
		wire.get()._receive(msg);

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
		logger.debug("exception caught", cause);
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
		}
		wire = Optional.empty();
	}

	private void debug(String log) {
		if(logger.isDebugEnabled()) {
			logger.debug(sym + "[" + id + "] " + log);
		}
	}

	private void trace(String log){
		if(logger.isTraceEnabled()){
			logger.trace(sym + "[" + id + "] " + log);
		}
	}

}
