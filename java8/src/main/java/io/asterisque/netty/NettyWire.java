/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.netty;

import io.asterisque.Message;
import io.asterisque.Wire;
import io.asterisque.conf.Config;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// NettyWire
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * Netty を使用した Wire 実装です。
 *
 * @author Takami Torao
 */
class NettyWire extends Wire {
	private static final Logger logger = LoggerFactory.getLogger(NettyWire.class);
	private final Config config;
	private final boolean server;
	private final CompletableFuture<Optional<SSLSession>> tls;
	private final ChannelHandlerContext context;
	private final String sym;

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 * Netty を使用した Wire を構築します。
	 *
	 * @param tls SSL セッションの Future
	 * @param server この Wire 端点がサーバ側の場合 true
	 * @param config 接続設定
	 * @param context チャネルコンテキスト
	 * @param dispatcher メッセージ受信時の処理
	 * @param executor メッセージ受信処理を起動するスレッドプール
	 */
	public NettyWire(CompletableFuture<Optional<SSLSession>> tls, boolean server, Config config,
									 ChannelHandlerContext context,
									 Consumer<Message> dispatcher, Consumer<Wire> disposer, Executor executor){
		super(
			config.sendBufferSize(), config.sendBufferLimit(), config.onSendBackpressupre(),
			config.receiveBufferSize(), Integer.MAX_VALUE,
			suspend -> context.channel().config().setAutoRead(! suspend), dispatcher, disposer, executor);
		this.config = config;
		this.server = server;
		this.tls = tls;
		this.context = context;
		this.sym = server? "S": "C";
	}

	// ==============================================================================================
	// 送信キュー 0/1 処理
	// ==============================================================================================
	/**
	 * 送信キューにメッセージが投入された時に Future を連結した送信処理を開始します。
	 */
	@Override
	protected void onSendQueueEmpty(boolean empty){
		if(! empty){
			send();
		}
	}

	// ==============================================================================================
	// メッセージの送信
	// ==============================================================================================
	/**
	 * 送信キューに格納されているメッセージを全て送信します。
	 */
	private void send(){
		Optional<Message> msg = nextSendMessage();
		if(msg.isPresent()){
			if(isClosed()){
				logger.debug("", new IOException(sym + ": cannot send on closed channel: " + msg));
			} else {
				if(logger.isTraceEnabled()){
					logger.trace(sym + ": send(" + msg + ")");
				}
				context.channel().writeAndFlush(msg.get()).addListener(f -> {
					if(! f.isSuccess()){
						logger.debug(sym + ": fail to send message: " + msg, f.cause());
						close();
					} else {
						send();
					}
				});
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public boolean isServer(){ return server; }

	/**
	 * {@inheritDoc}
	 */
	public Optional<SSLSession> getSSLSession() throws InterruptedException {
		try {
			return tls.get();
		} catch(ExecutionException ex){
			return Optional.empty();
		}
	}

	// ==============================================================================================
	// Wire のクローズ
	// ==============================================================================================
	/**
	 * Netty の channel をクローズします。
	 */
	public void close() {
		if(! isClosed()){
			if(logger.isTraceEnabled()){
				logger.trace(sym + ": close()");
			}
			context.channel().close();
			super.close();
		}
	}

	// ==============================================================================================
	// メッセージの送信
	// ==============================================================================================
	/**
	 * 送信キューに格納されているメッセージを全て送信します。
	 */
	void _receive(Message msg) throws InterruptedException {
		if(logger.isTraceEnabled()){
			logger.trace(sym + " _receive(" + msg + ")");
		}
		receive(msg);
	}

	public String toString(){
		return sym + ":" + context.channel().remoteAddress().toString();
	}

}

