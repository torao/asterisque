/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.cluster;

import java.security.Principal;
import java.util.*;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// MemoryRepository
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class MemoryRepository implements Repository {

	// ==============================================================================================
	// 新規 UUID の取得
	// ==============================================================================================
	/**
	 * {@inheritDoc}
	 */
	public UUID nextUUID(){
		return UUID.randomUUID();
	}

	private static final class Value {
		public final byte[] value;
		public final long expires;
		public Value(byte[] value, long expires){
			this.value = value;
			this.expires = expires;
		}
	}

	private static Timer timer = null;

	private static final Map<UUID, Value> Repository = new HashMap<>();

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void store(Optional<Principal> principal, UUID id, byte[] binary, long expires) {
		synchronized(Repository){
			if(Repository.size() == 0){
				timer(true);
			}
			Repository.put(id, new Value(binary, System.currentTimeMillis() + expires));
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Optional<byte[]> loadAndDelete(Optional<Principal> principal, UUID id) {
		synchronized(Repository){
			Value value = Repository.remove(id);
			if(Repository.size() == 0){
				timer(false);
			}
			if(value == null || value.expires < System.currentTimeMillis()) {
				return Optional.empty();
			} else {
				return Optional.of(value.value);
			}
		}
	}

	private static void timer(boolean on){
		if(on){
			timer = new Timer("MemoryRepository", true);
			timer.scheduleAtFixedRate(new TimerTask() {
				@Override
				public void run() {
					synchronized(Repository){
						long now = System.currentTimeMillis();
						Repository.entrySet().stream()
							.filter(e -> e.getValue().expires < now)
							.map(Map.Entry::getKey)
							.forEach(Repository::remove);
					}
				}
			}, 1000, 1000);
		} else {
			timer.cancel();
			timer = null;
		}
	}
}
