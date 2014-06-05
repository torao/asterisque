/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.conf;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// ListenConfig
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

import io.asterisque.Session;

import java.util.function.Consumer;

/**
 * @author Takami Torao
 */
public class ListenConfig extends ConnectConfig {
	@SuppressWarnings("unchecked")
	private static final Class<Consumer<Session>> SessionConsumerType = (Class<Consumer<Session>>)(Class<?>)Consumer.class;

	public static final Key<Integer> BACKLOG = new Key<>("backlog", Integer.class);
	/** このサーバ側で許容される ping の最小/最大間隔 */
	public static final Key<Integer> MIN_PING = new Key<>("minPing", Integer.class);
	public static final Key<Integer> MAX_PING = new Key<>("maxPing", Integer.class);

	public ListenConfig(){ super(); }
	public ListenConfig(ListenConfig init){ super(init); }

	public ListenConfig backlog(int backlog){
		set(BACKLOG, backlog);
		return this;
	}
	public int backlog(){
		return getOrElse(BACKLOG, 10);
	}
	public ListenConfig bridge(NetworkBridge bridge){
		set(NETWORK_BRIDGE, bridge);
		return this;
	}
	public ListenConfig minPing(int minPing){
		set(MIN_PING, minPing);
		return this;
	}
	public int minPing(){
		return getOrElse(MIN_PING, 1 * 1000);
	}
	public ListenConfig maxPing(int maxPing){
		set(MAX_PING, maxPing);
		return this;
	}
	public int maxPing(){
		return getOrElse(MAX_PING, 10 * 1000);
	}

}
