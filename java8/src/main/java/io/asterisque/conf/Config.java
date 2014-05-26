/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.conf;

import io.asterisque.Debug;
import io.asterisque.NetworkBridge;
import io.asterisque.codec.Codec;
import io.asterisque.codec.MessagePackCodec;

import javax.net.ssl.SSLContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Config
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
public abstract class Config {
	@SuppressWarnings("unchecked")
	private static final Class<Consumer<Boolean>> BooleanConsumerType = (Class<Consumer<Boolean>>) (Class<?>)Consumer.class;
	/** Wire 上で使用するコーデック */
	public static final Key<Codec> CODEC = new Key<>("codec", Codec.class);
	/** サーバ認証を行うための SSL 証明書 (Noneの場合は非SSL接続) */
	public static final Key<SSLContext> SSL_CONTEXT = new Key<>("sslContext", SSLContext.class);
	/** 接続に使用するネットワークブリッジ */
	public static final Key<NetworkBridge> NETWORK_BRIDGE = new Key<>("networkBridge", NetworkBridge.class);
	public static final Key<Integer> SEND_BUFFER_SIZE = new Key<>("sendBufferSize", Integer.class);
	public static final Key<Integer> SEND_BUFFER_LIMIT = new Key<>("sendBufferLimit", Integer.class);
	public static final Key<Consumer<Boolean>> ON_SEND_BACKPRESSURE = new Key<>("onSendBackpressure", BooleanConsumerType);
	public static final Key<Integer> RECEIVE_BUFFER_SIZE = new Key<>("receiveBufferSize", Integer.class);

	private final Optional<Config> init;
	private final Map<Key<?>,Object> config;

	public Config(){
		this.init = Optional.empty();
		this.config = new HashMap<>();
	}

	public Config(Config init){
		this.init = Optional.of(init);
		this.config = new HashMap<>();
	}

	public Codec codec(){
		return getOrElse(CODEC, MessagePackCodec.getInstance());
	}
	public NetworkBridge bridge(){
		return getOrElse(NETWORK_BRIDGE, NetworkBridge.DefaultBridge);
	}
	public Optional<SSLContext> sslContext(){
		return get(SSL_CONTEXT);
	}
	public int sendBufferSize(){
		return getOrElse(SEND_BUFFER_SIZE, 10);
	}
	public int sendBufferLimit(){
		return getOrElse(SEND_BUFFER_LIMIT, 10);
	}
	public Consumer<Boolean> onSendBackpressupre(){
		return getOrElse(ON_SEND_BACKPRESSURE, b -> {
		});
	}
	public int receiveBufferSize(){
		return getOrElse(RECEIVE_BUFFER_SIZE, 10);
	}

	public <T> Config set(Key<T> key, T value){
		this.config.put(key, value);
		return this;
	}

	public <T> Optional<T> get(Key<T> key){
		if(config.containsKey(key)) {
			Object value = config.get(key);
			return Optional.of(key.clazz.cast(value));
		} else if(init.isPresent()){
			return init.get().get(key);
		} else {
			return Optional.empty();
		}
	}

	public <T> T getOrElse(Key<T> key, T def){
		Optional<T> value = get(key);
		if(value.isPresent()){
			return value.get();
		} else {
			return def;
		}
	}

	public void verify(){
		String[] keys = config.keySet().stream().filter(k -> k.required).filter(k -> ! config.containsKey(k)).toArray(String[]::new);
		if(keys.length > 0){
			throw new ConfigurationException("configuration required: " + Debug.toString(keys));
		}
	}

	public static final class Key<T> implements Comparable<Key> {
		public final String key;
		public final Class<T> clazz;
		public final boolean required;
		public Key(String key, Class<T> clazz, boolean required){
			this.key = key;
			this.clazz = clazz;
			this.required = required;
		}
		public Key(String key, Class<T> clazz){
			this(key, clazz, false);
		}
		@Override
		public int compareTo(Key o) { return this.key.compareTo(o.key); }
		@Override
		public int hashCode(){ return key.hashCode(); }
		@Override
		public boolean equals(Object obj){
			return (obj instanceof Key)
				&& ((Key)obj).key.equals(this.key)
				&& ((Key)obj).clazz.equals(this.clazz);
		}
	}

}
