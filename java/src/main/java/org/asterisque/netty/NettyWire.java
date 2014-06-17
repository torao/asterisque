/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.asterisque.Debug;
import org.asterisque.LocalNode;
import org.asterisque.Message;
import org.asterisque.Wire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSession;
import java.net.SocketAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// NettyWire
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * Netty を使用した Wire 実装です。
 *
 * @author Takami Torao
 */
class NettyWire implements Wire {
	private static final Logger logger = LoggerFactory.getLogger(NettyWire.class);

	private final LocalNode node;
	private final SocketAddress local;
	private final SocketAddress remote;
	private final boolean server;
	private final CompletableFuture<Optional<SSLSession>> tls;
	private final ChannelHandlerContext context;
	private final String sym;
	private final AtomicBoolean closing = new AtomicBoolean(false);
	private boolean writable = false;

	private Optional<Plug> plug = Optional.empty();
	/**
	 * メッセージの送信が完了した時に次のメッセージを取り出して送信する Future リスナです。送信に失敗した場合は Wire
	 * をクローズします。
	 */
	private final GenericFutureListener<Future<Void>> writeComplete = future -> {
		if(future.isSuccess()){
			writeNext();
		} else {
			logger.error("message write failure, closing wire: " + this, future.cause());
			close();
		}
	};

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 * Netty を使用した Wire を構築します。
	 *
	 * @param tls SSL セッションの Future
	 * @param server この Wire 端点がサーバ側の場合 true
	 * @param context チャネルコンテキスト
	 */
	public NettyWire(LocalNode node, SocketAddress local, SocketAddress remote,
									 boolean server, CompletableFuture<Optional<SSLSession>> tls,
									 ChannelHandlerContext context){
		this.node = node;
		this.local = local;
		this.remote = remote;
		this.server = server;
		this.tls = tls;
		this.context = context;
		this.sym = server? "S": "C";

		// Channel のクローズと連動してこの Wire もクローズするように設定
		context.channel().closeFuture().addListener(future -> close());
	}

	// ==============================================================================================
	// ローカルノードの参照
	// ==============================================================================================
	/**
	 * {@inheritDoc}
	 */
	public LocalNode node(){ return node; }

	// ==============================================================================================
	// ローカルノードの参照
	// ==============================================================================================
	/**
	 * {@inheritDoc}
	 */
	public SocketAddress local(){ return local; }

	// ==============================================================================================
	// リモートノードの参照
	// ==============================================================================================
	/**
	 * {@inheritDoc}
	 */
	public SocketAddress remote(){ return remote; }

	// ==============================================================================================
	// サーバ側判定
	// ==============================================================================================
	/**
	 * {@inheritDoc}
	 */
	public boolean isServer(){ return server; }

	// ==============================================================================================
	// イベントハンドラの設定
	// ==============================================================================================
	/**
	 * {@inheritDoc}
	 */
	public void setPlug(Optional<Plug> plug) {
		this.plug = plug;
		if(plug.isPresent()){
			writeNext();
		}
	}

	// ==============================================================================================
	// メッセージ送信可能設定
	// ==============================================================================================
	/**
	 * {@inheritDoc}
	 */
	public void setWritable(boolean writable){
		this.writable = writable;
		writeNext();
	}

	// ==============================================================================================
	// メッセージ受信可能設定
	// ==============================================================================================
	/**
	 * {@inheritDoc}
	 */
	public void setReadable(boolean readable){
		context.channel().config().setAutoRead(readable);
	}

	// ==============================================================================================
	// SSL セッションの参照
	// ==============================================================================================
	/**
	 * {@inheritDoc}
	 */
	public Optional<SSLSession> getSSLSession() {
		try {
			if(! tls.isDone()) {
				throw new IllegalStateException("SSL handshake is not complete");
			}
			return tls.get();
		} catch(InterruptedException ex){
			// wait は発生しないので割り込み例外も発生しないはず
			throw new IllegalStateException("unexpected wait", ex);
		} catch(ExecutionException ex){
			throw new IllegalStateException("SSL handshake failure", ex);
		}
	}

	// ==============================================================================================
	// Wire のクローズ
	// ==============================================================================================
	/**
	 * Netty の channel をクローズしスタブに伝達します。再帰呼び出しや 2 度目以降の呼び出しでは何も行いません。
	 */
	public void close() {
		if(closing.compareAndSet(false, true)){
			if(logger.isTraceEnabled()){
				logger.trace(sym + ": close()");
			}
			context.channel().close();
			plug.ifPresent(p -> p.onClose(this) );
		}
	}

	// ==============================================================================================
	// インスタンスの文字列化
	// ==============================================================================================
	/**
	 * {@inheritDoc}
	 */
	public String toString(){
		return sym + ":" + Debug.toString(context.channel().remoteAddress());
	}

	// ==============================================================================================
	// メッセージの送信
	// ==============================================================================================
	/**
	 * 次のメッセージを取り出して下層の channel に出力します。
	 */
	private void writeNext(){
		if(writable && ! closing.get()){
			plug.ifPresent(p -> {
				Message msg = p.produce();
				if(logger.isTraceEnabled()) {
					logger.trace(sym + ": send(" + msg + ")");
				}
				// 出力完了の通知を受けたら次のメッセージを送信する処理を実行
				context.channel().writeAndFlush(msg).addListener(writeComplete);
			});
		}
	}

	// ==============================================================================================
	// メッセージの送信
	// ==============================================================================================
	/**
	 * 送信キューに格納されているメッセージを全て送信します。
	 */
	void receive(Message msg) {
		if(logger.isTraceEnabled()){
			logger.trace(sym + ": receive(" + msg + ")");
		}
		plug.ifPresent(p -> p.consume(msg));
	}

}

