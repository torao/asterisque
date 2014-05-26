/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque;

import io.asterisque.cluster.Repository;

import java.util.concurrent.CompletableFuture;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// ServerSession
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
public class ServerSession {
	private final Repository repository;
	private final CompletableFuture<NetworkBridge.Server> server;

	ServerSession(Repository repository, CompletableFuture<NetworkBridge.Server> server){
		this.repository = repository;
		this.server = server;
	}
}
