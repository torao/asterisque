/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.util;

import java.util.concurrent.atomic.AtomicInteger;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// ConditionalBarrier
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
public final class LooseBarrier {

	private final Object signal = new Object();
	private final AtomicInteger lock = new AtomicInteger(0);

	public LooseBarrier(){ }

	public void lock(boolean lock){
		if(lock){
			this.lock.incrementAndGet();
		} else if(this.lock.decrementAndGet() == 0){
			synchronized(signal){
				signal.notifyAll();
			}
		}
	}

	public void barrier(Runnable exec) throws InterruptedException {
		if(lock.get() != 0){
			synchronized(signal){
				while(lock.get() != 0){
					signal.wait();
				}
			}
		}
		exec.run();
	}
}
