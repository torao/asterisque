/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslHandler;
import org.asterisque.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import java.net.SocketAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Netty
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * 非同期 I/O 実装に Netty を使用したネットワークブリッジです。非同期 SSL に対応しています。
 *
 * @author Takami Torao
 */
public class Netty implements Bridge {
	private static final Logger logger = LoggerFactory.getLogger(Netty.class);

	private final AtomicReference<NioEventLoopGroup> worker = new AtomicReference<>();
	private final AtomicBoolean closing = new AtomicBoolean(false);

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 *
	 */
	public Netty(){ }

	// ==============================================================================================
	// ワーカー
	// ==============================================================================================
	/**
	 *
	 */
	private NioEventLoopGroup worker(){
		if(closing.get()){
			throw new IllegalStateException("netty bridge already closed");
		}
		if(worker.get() == null){
			NioEventLoopGroup w = new NioEventLoopGroup();
			if(! worker.compareAndSet(null, w)){
				w.shutdownGracefully();
			} else {
				logger.debug("worker NioEventLoop created");
			}
		}
		return worker.get();
	}

	// ==============================================================================================
	// 接続の実行
	// ==============================================================================================
	/**
	 * 指定されたアドレスへの接続を行います。
	 * @param options 接続設定
	 * @return Wire の Future
	 */
	public CompletableFuture<Wire> newWire(LocalNode node, SocketAddress address, Options options) {
		Bootstrap client = new Bootstrap();
		CompletableFuture<Wire> future = new CompletableFuture<>();

		Initializer factory = new Initializer(node, false, options, wire -> {
			logger.debug("onConnect(" + wire + ")");
			future.complete(wire);
		});

		client
			.group(worker())
			.channel(NioSocketChannel.class)
			.remoteAddress(address)
			.option(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
			.handler(factory);

		client.connect(address).addListener(f -> {
			if(f.isSuccess()) {
				logger.debug("connection success");
			} else {
				logger.debug("connection failure");
				future.completeExceptionally(f.cause());
			}
		});
		return future;
	}

	// ==============================================================================================
	// 接続受付の実行
	// ==============================================================================================
	/**
	 * 指定されたアドレス上で接続の受け付けを開始します。
	 *
	 * @return Server の Future
	 */
	public CompletableFuture<Server> newServer(LocalNode node, SocketAddress address, Options options, Consumer<Wire> onAccept) {
		NioEventLoopGroup master = new NioEventLoopGroup();		// サーバソケットごとに生成、消滅
		ServerBootstrap server = new ServerBootstrap();
		CompletableFuture<Server> future = new CompletableFuture<>();

		Initializer factory = new Initializer(node, true, options, wire -> {
			logger.debug("onAccept(" + wire + ")");
			onAccept.accept(wire);
		});

		server
			.group(master, worker())
			.channel(NioServerSocketChannel.class)
			.localAddress(address)
			.option(ChannelOption.SO_BACKLOG, options.get(Options.KEY_SERVER_BACKLOG).get())
			.childOption(ChannelOption.TCP_NODELAY, Boolean.TRUE)
			.childHandler(factory);

		server.bind().addListener(f -> {
			if(f.isSuccess()){
				logger.info("startup server: " + Debug.toString(address));
				future.complete(new Server(node, address, options) {
					@Override
					public void close() {
						master.shutdownGracefully();
					}
				});
			} else {
				logger.error("server bind failure: " + Debug.toString(address), f.cause());
				future.completeExceptionally(f.cause());
				master.shutdownGracefully();
			}
		});
		return future;
	}

	// ==============================================================================================
	// クローズ
	// ==============================================================================================
	/**
	 * {@inheritDoc}
	 */
	public void close(){
		logger.trace("close()");
		if(closing.compareAndSet(false, true)){
			NioEventLoopGroup w = worker.getAndSet(null);
			if(w != null){
				w.shutdownGracefully();
			}
		}
	}

	private static class Initializer extends ChannelInitializer {
		private final LocalNode node;
		private final boolean isServer;
		private final Options options;
		private final Consumer<NettyWire> onWireCreate;
		private final String sym;

		// ============================================================================================
		// コンストラクタ
		// ============================================================================================
		/**
		 */
		public Initializer(LocalNode node, boolean isServer, Options options, Consumer<NettyWire> onWireCreate) {
			this.node = node;
			this.isServer = isServer;
			this.options = options;
			this.onWireCreate = onWireCreate;
			this.sym = isServer? "S": "C";
		}

		// ============================================================================================
		// チャネルの初期化
		// ============================================================================================
		/**
		 * パイプラインを構築します。
		 */
		@Override
		public void initChannel(Channel ch){
			logger.trace(sym + ": initChannel(" + ch + ")");
			ChannelPipeline pipeline = ch.pipeline();

			Optional<SslHandler> sslHandler;
			if(options.get(Options.KEY_SSL_CONTEXT).isPresent()) {
				// SSL あり
				SSLEngine engine = options.get(Options.KEY_SSL_CONTEXT).get().createSSLEngine();
				engine.setUseClientMode(! isServer);
				engine.setNeedClientAuth(true);					// TODO クライアント認証なしの接続を許可するか
				if(logger.isTraceEnabled()) {
					logger.trace(sym + ": CipherSuites: ${engine.getEnabledCipherSuites.mkString(", ")}");
					logger.trace(sym + ": Protocols: ${engine.getEnabledProtocols.mkString(", ")}");
				}
				SslHandler handler = new SslHandler(engine);
				pipeline.addLast("tls", handler);
				sslHandler = Optional.of(handler);
			} else {
				// SSL なし
				logger.trace(sym + ": insecure connection");
				sslHandler = Optional.empty();
			}

			pipeline.addLast("io.asterisque.frame.encoder", new MessageEncoder(options.get(Options.KEY_CODEC).get()));
			pipeline.addLast("io.asterisque.frame.decoder", new MessageDecoder(options.get(Options.KEY_CODEC).get()));
			pipeline.addLast("io.asterisque.service",
				new WireConnect(node, ch.localAddress(), ch.remoteAddress(), isServer, sslHandler, onWireCreate, options));
		}
	}
}
