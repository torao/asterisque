/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

import org.asterisque.codec.Codec;
import org.asterisque.codec.MessagePackCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Options
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * 接続オプションを表すクラスです。
 *
 * @author Takami Torao
 */
public class Options {
	private static final Logger logger = LoggerFactory.getLogger(Options.class);
	private final Map<Key<?>,Object> config = new HashMap<>();

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 * 空の設定を構築します。
	 */
	public Options(){ }

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 * 指定された初期値を持つ設定を構築します。
	 * {@code init} の内容はこのインスタンスの初期状態としてコピーされますが {@code init} の変更とは連動しません。
	 */
	public Options(Options init){ this.config.putAll(init.config); }

	// ==============================================================================================
	// 値の参照
	// ==============================================================================================
	/**
	 * 指定されたキーに対する値を参照します。値が設定されていない場合は empty を返します。
	 */
	public <T> Optional<T> get(Key<T> key){
		Optional<T> value = _get(key);
		if(logger.isTraceEnabled()){
			logger.trace(key + "=" + Debug.toString(value));
		}
		return value;
	}

	// ==============================================================================================
	// 値の参照
	// ==============================================================================================
	/**
	 * 指定されたキーに対する値を参照します。値が設定されていない場合は empty を返します。
	 */
	private <T> Optional<T> _get(Key<T> key){
		if(config.containsKey(key)){
			Object value = config.get(key);
			if(value instanceof String && ! key.type.equals(String.class)){
				return key.parse((String)value);
			}
			if(key.type.isAssignableFrom(value.getClass())) {
				return Optional.of(key.type.cast(value));
			} else {
				logger.warn(key.key + ": current value " + Debug.toString(value) + " of type " + value.getClass().getName()
					+ " is not compatible the required type " + key.type.getName() + "; apply default "
					+ Debug.toString(key.defaultValue));
			}
		}
		return key.defaultValue;
	}

	// ==============================================================================================
	// 値の参照
	// ==============================================================================================
	/**
	 * 指定されたキーに対する値を参照します。値が設定されていない場合は指定されたデフォルト値を評価して返します。
	 */
	public <T> T getOrElse(Key<T> key, Supplier<T> def){
		return get(key).orElseGet(def);
	}

	// ==============================================================================================
	// 値の設定
	// ==============================================================================================
	/**
	 * 指定されたキーに対する値を設定します。null を設定することは出来ません。
	 */
	public <T, U extends T> Options set(Key<T> key, U value){
		if(value == null){
			throw new NullPointerException("null is not acceptable for key: " + key);
		}
		config.put(key, value);
		return this;
	}

	// ##############################################################################################
	/*
	 * Scala で options.set(Key[Integer], Int) として参照する場合は Auto Boxing が効かず型パラメータとの整合
	 * 性でコンパイルエラーになるため、プリミティブ型には明示的にメソッドを用意している (暗黙的型変換なども使用したが
	 * 型パラメータがあるとうまく認識しない)。
	 */

	public Options set(Key<Boolean> key, boolean value){ set(key, Boolean.valueOf(value)); return this; }
	public Options set(Key<Integer> key, int value){ set(key, Integer.valueOf(value)); return this; }

	public static final Key<Bridge> KEY_BRIDGE            = new Key<>("org.asterisque.bridge", Bridge.class);
	public static final Key<SSLContext> KEY_SSL_CONTEXT   = new Key<>("org.asterisque.ssl", SSLContext.class);
	public static final Key<Codec> KEY_CODEC              = new Key<>("org.asterisque.codec", Codec.class, MessagePackCodec.getInstance());
	// TODO 他に SocketOption など

	//
	public static final IntKey KEY_READ_SOFT_LIMIT  = new IntKey("org.asterisque.wire.read.softlimit", 1024);
	public static final IntKey KEY_READ_HARD_LIMIT  = new IntKey("org.asterisque.wire.read.hardlimit", Integer.MAX_VALUE);
	public static final IntKey KEY_WRITE_SOFT_LIMIT = new IntKey("org.asterisque.wire.write.softlimit", 1024);
	public static final IntKey KEY_WRITE_HARD_LIMIT = new IntKey("org.asterisque.wire.write.hardlimit", Integer.MAX_VALUE);

	// Client Options
	public static final IntKey KEY_PING_REQUEST = new IntKey("org.asterisque.client.ping", 3);
	public static final IntKey KEY_SESSION_TIMEOUT_REQUEST = new IntKey("org.asterisque.client.session.timeout", 30 * 60);

	// Server Options
	public static final IntKey KEY_SERVER_BACKLOG = new IntKey("org.asterisque.server.backlog", 50);
	public static final IntKey KEY_MAX_PING = new IntKey("org.asterisque.server.ping.max", 60);
	public static final IntKey KEY_MIN_PING = new IntKey("org.asterisque.server.ping.min", 1);
	public static final IntKey KEY_MAX_SESSION_TIMEOUT = new IntKey("org.asterisque.server.session.timeout.max", 60 * 60);
	public static final IntKey KEY_MIN_SESSION_TIMEOUT = new IntKey("org.asterisque.server.session.timeout.min", 60);

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// Key
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * {@link Options} の値を参照するためのキーです。文字列としての識別子と値の型を持ちます。
	 * @param <T> 値の型
	 */
	public static class Key<T> implements Serializable, Comparable<Key<T>> {
		/** このキーの文字列としての識別子 */
		public final String key;
		/** このキーの値の型 */
		public final Class<T> type;
		/** このキーのデフォルト値 */
		public final Optional<T> defaultValue;
		private Key(String key, Class<T> type, Optional<T> def){
			if(key == null || type == null){ throw new NullPointerException(); }
			this.key = key;
			this.type = type;
			this.defaultValue = def;
		}
		public Key(String key, Class<T> type){ this(key, type, Optional.empty()); }
		public Key(String key, Class<T> type, T def){ this(key, type, Optional.of(def)); }
		public Optional<T> parse(String value){ return Optional.empty(); }
		@Override
		public int compareTo(Key<T> o) { return this.key.compareTo(o.key); }
		@Override
		public String toString(){ return key; }
		@Override
		public int hashCode(){ return key.hashCode(); }
		@Override
		public boolean equals(Object o){
			if(! (o instanceof Key)){
				return false;
			}
			Key<?> other = (Key<?>)o;
			return this.key.equals(other.key);
		}
	}

	public static final class IntKey extends Key<Integer> {
		public IntKey(String key, int def){ super(key, Integer.class, Optional.of(def)); }
		public IntKey(String key){ super(key, Integer.class); }
		@Override
		public Optional<Integer> parse(String value){
			try{
				return Optional.of(Integer.parseInt(value));
			} catch(NumberFormatException ex){
				logger.warn(key + ": fail to parse as int: " + Debug.toString(value));
				return Optional.empty();
			}
		}
	}

	public static final class StringKey extends Key<String> {
		public StringKey(String key, String def){ super(key, String.class, Optional.of(def)); }
		public StringKey(String key){ super(key, String.class); }
		@Override
		public Optional<String> parse(String value){ return Optional.of(value); }
	}

}
