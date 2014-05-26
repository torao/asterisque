/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.netty;

import io.asterisque.Message;
import io.asterisque.NetworkBridge;
import io.asterisque.Wire;
import io.asterisque.conf.ConfigurationException;
import io.asterisque.conf.ConnectConfig;
import io.asterisque.conf.ListenConfig;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Netty
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * 非同期 I/O 実装に Netty を使用したネットワークブリッジです。非同期 SSL に対応しています。
 *
 * @author Takami Torao
 */
public class Netty implements NetworkBridge {
	private static final Logger logger = LoggerFactory.getLogger(Netty.class);

	public Netty(){ }

	// ==============================================================================================
	// 接続の実行
	// ==============================================================================================
	/**
	 * 指定されたアドレスへの接続を行います。
	 * @param config 接続設定
	 * @return Wire の Future
	 */
	public CompletableFuture<Wire> connect(ConnectConfig config, Executor executor, Consumer<Message> dispatcher, Consumer<Wire> disposer) {
		if(config.address().isPresent()) {
			NioEventLoopGroup group = new NioEventLoopGroup();
			Bootstrap client = new Bootstrap();
			CompletableFuture<Wire> future = new CompletableFuture<>();
			Initializer factory = new Initializer(config, false, executor, wire -> {
				logger.debug("onConnect(" + wire + ")");
				future.complete(wire);
			}, dispatcher, wire -> {
				shutdown(client);
				disposer.accept(wire);
			});
			client
				.group(group)
				.channel(NioSocketChannel.class)
				.remoteAddress(config.address().get())
				.option(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
				.handler(factory);
			client.connect(config.address().get()).addListener(f -> {
				if(f.isSuccess()) {
					logger.debug("connection success");
				} else {
					logger.debug("connection failure");
					future.completeExceptionally(f.cause());
				}
			});
			return future;
		} else {
			CompletableFuture<Wire> c = new CompletableFuture<>();
			c.completeExceptionally(new ConfigurationException("remote address is not specified"));
			return c;
		}
	}

	// ==============================================================================================
	// 接続受付の実行
	// ==============================================================================================
	/**
	 * 指定されたアドレス上で接続の受け付けを開始します。
	 *
	 * @return Server の Future
	 */
	public CompletableFuture<Server> listen(ListenConfig config, Executor executor, Consumer<Wire> onAccept) {
		if(config.address().isPresent()) {
			Initializer factory = new Initializer(config, true, executor, wire -> {
				logger.debug("onAccept(" + wire + ")");
				onAccept.accept(wire);
			});
			NioEventLoopGroup masterGroup = new NioEventLoopGroup();
			NioEventLoopGroup workerGroup = new NioEventLoopGroup();
			ServerBootstrap server = new ServerBootstrap();
			server
				.group(masterGroup, workerGroup)
				.channel(NioServerSocketChannel.class)
				.localAddress(config.address().get())
				.option(ChannelOption.SO_BACKLOG, config.backlog())
				.childOption(ChannelOption.TCP_NODELAY, Boolean.TRUE)
				.childHandler(factory);

			CompletableFuture<Server> future = new CompletableFuture<>();
			server.bind().addListener(f -> {
				if(f.isSuccess()){
					logger.debug("operationComplete(success)");
					future.complete(new Server(config.address().get()) {
						@Override
						public void close() {
							shutdown(server);
						}
					});
				} else {
					logger.debug("operationComplete(failure)");
					future.completeExceptionally(f.cause());
				}
			});
			return future;
		} else {
			CompletableFuture<Server> c = new CompletableFuture<>();
			c.completeExceptionally(new ConfigurationException("remote address is not specified"));
			return c;
		}
	}

	private void shutdown(Bootstrap bootstrap) {
		logger.debug("closing netty client bootstrap");
		bootstrap.group().shutdownGracefully();
	}

	private void shutdown(ServerBootstrap bootstrap) {
		logger.debug("closing netty server bootstrap");
		bootstrap.group().shutdownGracefully();
		bootstrap.childGroup().shutdownGracefully();
	}

	private class Initializer extends ChannelInitializer {
		private final ConnectConfig config;
		private final boolean isServer;
		private final Executor executor;
		private final Consumer<NettyWire> onWireCreate;
		private final Consumer<Message> dispatcher;
		private final Consumer<Wire> disposer;
		private final String sym;
		public Initializer(ConnectConfig config, boolean isServer, Executor executor, Consumer<NettyWire> onWireCreate, Consumer<Message> dispatcher, Consumer<Wire> disposer) {
			this.config = config;
			this.isServer = isServer;
			this.executor = executor;
			this.onWireCreate = onWireCreate;
			this.dispatcher = dispatcher;
			this.disposer = disposer;
			this.sym = isServer? "S": "C";
		}

		// ==============================================================================================
		//
		// ==============================================================================================
		@Override
		public void initChannel(Channel ch){
			logger.trace(sym + ": initChannel(" + ch + ")");
			ChannelPipeline pipeline = ch.pipeline();
			Optional<SslHandler> sslHandler;
			if(config.sslContext().isPresent()) {
				SSLEngine engine = config.sslContext().get().createSSLEngine();
				engine.setUseClientMode(!isServer);
				engine.setNeedClientAuth(true);
				if(logger.isTraceEnabled()) {
					logger.trace(sym + ": CipherSuites: ${engine.getEnabledCipherSuites.mkString(", ")}");
					logger.trace(sym + ": Protocols: ${engine.getEnabledProtocols.mkString(", ")}");
				}
				SslHandler handler = new SslHandler(engine);
				pipeline.addLast("tls", handler);
				sslHandler = Optional.of(handler);
			} else {
				logger.trace(sym + ": insecure connection");
				sslHandler = Optional.empty();
			}
			pipeline.addLast("io.asterisque.frame.encoder", new MessageEncoder(config.codec()));
			pipeline.addLast("io.asterisque.frame.decoder", new MessageDecoder(config.codec()));
			pipeline.addLast("io.asterisque.service", new WireConnect(sslHandler, isServer, onWireCreate, executor, dispatcher, disposer, config));
		}
	}
}
