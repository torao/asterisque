/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Node
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
public class Node implements AutoCloseable {
	private static final Logger logger = LoggerFactory.getLogger(Node.class);

	public final String name;
	public final Optional<SSLContext> certificates;

	private final AtomicBoolean closing = new AtomicBoolean(false);
	private final AtomicBoolean closed = new AtomicBoolean(false);
	private final Collection<Closeable> endpoints = new LinkedList<>();

	public Node(String name, Optional<SSLContext> certs){
		this.name = name;
		this.certificates = certs;
	}

	public Client connect(){
		return null;
	}

	public Server listen(){
		return null;
	}

	public boolean closed(){
		// "クローズ中" は内部的な状態であり、外部からは "クローズ済み" と等価
		return closing.get();
	}

	@Override
	public void close(){
		if(closing.compareAndSet(false, true)){
			endpoints.forEach(c -> {
				try {
					c.close();
				} catch(Exception ex){
					logger.error("fail to close endpoint: " + c, ex);
				}
			});
			endpoints.clear();
			closed.set(true);
		}
	}

}
