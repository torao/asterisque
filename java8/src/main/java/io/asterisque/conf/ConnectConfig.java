/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.conf;

import io.asterisque.NetworkBridge;
import io.asterisque.codec.Codec;

import javax.net.ssl.SSLContext;
import java.net.SocketAddress;
import java.util.Optional;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// ConnectConfig
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
public class ConnectConfig extends Config {
	/*
	private final int backlog;
	private final int sendMessageBufferSize;
	private final int sendMessageBufferLimit;
	private final Consumer<Boolean> onBackPressure;
	private final int recvMessageBufferSize;

	private final Optional<SslHandler> sslHandler;
	private final boolean isServer;
	private final Consumer<NettyWire> onWireCreate;
	private final int sendAdvisoryLimit;
	private final int sendBlockingLimit;
	private final Consumer<Boolean> sendBackPressure;
	private final int recvAdvisoryLimit;

	private final Consumer<Message> dispatcher;
	private final Executor executor;
	private final String sym;
	 */

	/** バインド先のアドレス */
	public static final Key<SocketAddress> ADDRESS = new Key<>("address", SocketAddress.class, true);
	/** クライアント側のネットワーク環境に応じた ping 間隔の要求値 (最終的にサーバ側で決定する) */
	public static final Key<Integer> PING = new Key<>("ping", Integer.class);

	public ConnectConfig(){ super(); }
	public ConnectConfig(ListenConfig init){ super(init); }

	public ConnectConfig codec(Codec codec){
		set(CODEC, codec);
		return this;
	}
	public ConnectConfig sslContext(SSLContext sslContext){
		set(SSL_CONTEXT, sslContext);
		return this;
	}
	public ConnectConfig sendBufferSize(int sendBufferSize){
		set(SEND_BUFFER_SIZE, sendBufferSize);
		return this;
	}
	public ConnectConfig sendBufferLimit(int sendBufferLimit){
		set(SEND_BUFFER_LIMIT, sendBufferLimit);
		return this;
	}
	public ConnectConfig bridge(NetworkBridge bridge){
		set(NETWORK_BRIDGE, bridge);
		return this;
	}
	public ConnectConfig onSendBackpressupre(Consumer<Boolean> onSendBackpressupre){
		set(ON_SEND_BACKPRESSURE, onSendBackpressupre);
		return this;
	}
	public ConnectConfig receiveBufferSize(int receiveBufferSize){
		set(RECEIVE_BUFFER_SIZE, receiveBufferSize);
		return this;
	}

	public ConnectConfig address(SocketAddress address){
		set(ADDRESS, address);
		return this;
	}
	public Optional<SocketAddress> address(){
		return get(ADDRESS);
	}
	public ConnectConfig ping(int ping){
		set(PING, ping);
		return this;
	}
	public int ping(){
		return getOrElse(PING, 3 * 1000);
	}

}
