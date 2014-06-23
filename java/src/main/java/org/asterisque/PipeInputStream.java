/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

import org.asterisque.message.Block;
import org.asterisque.util.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// PipeInputStream
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * パイプ上で受信した Block メッセージのバイナリデータをストリームとして参照するためのクラス。
 *
 * キューとしての役割もあります。
 */
class PipeInputStream extends InputStream implements Consumer<Block> {
	private static final Logger logger = LoggerFactory.getLogger(PipeInputStream.class);

	private final CircuitBreaker breaker;
	private final String signature;
	private final int maxQueueSize;

	public PipeInputStream(CircuitBreaker breaker, int maxSize, String signature){
		this.breaker = breaker;
		this.maxQueueSize = maxSize;
		this.signature = signature;
	}

	/**
	 * パイプから受信した Block メッセージを保持するためのキュー。
	 */
	private BlockingQueue<Block> receiveQueue = new LinkedBlockingQueue<>();

	/**
	 * 読み出し中の ByteBuffer。
	 */
	private Optional<ByteBuffer> processing = Optional.empty();

	/**
	 * ストリーム上で EOF を検知したかのフラグ。
	 */
	private boolean eof = false;

	/**
	 * インスタンスの利用側によってクローズされたかのフラグ。
	 */
	private boolean closed = false;

	public boolean isClosed(){
		return closed;
	}

	public void accept(Block block) {
		if(!closed) {
			if(logger.isTraceEnabled()) {
				logger.trace(signature + ": apply(" + block + ") enqueueing specified block message as stream data");
			}
			receiveQueue.add(block);
			breaker.increment();
			int size = receiveQueue.size();
			if(size > maxQueueSize && ! block.eof){
				logger.error(signature + ": queue for pipe overflow; " + size + " / " + maxQueueSize);
				close();
			}
		}
	}

	public int read() throws IOException {
		Optional<ByteBuffer> buffer = processingBuffer();
		if(buffer.isPresent()) {
			ensureOpen();
			return buffer.get().get() & 0xFF;
		} else {
			return -1;
		}
	}

	@Override
	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}

	@Override
	public int read(byte[] b, int offset, int length) throws IOException{
		Optional<ByteBuffer> buffer = processingBuffer();
		if(buffer.isPresent()){
			ensureOpen();
			int len = Math.min(length, buffer.get().remaining());
			buffer.get().get(b, offset, len);
			return len;
		} else {
			return -1;
		}
	}

	@Override
	public void close(){
		closed = true;
		logger.trace(signature + ": close()");
	}

	private Optional<ByteBuffer> processingBuffer() throws IOException {
		if(eof){
			return Optional.empty();
		} else if(processing.isPresent() && processing.get().hasRemaining()) {
			return processing;
		} else if(receiveQueue.isEmpty() && closed){
			// EOF 到着前に何らかの理由でクローズされた (キューのオーバーフローなど)
			throw new IOException("stream closed");
		} else try {
			Block block = receiveQueue.take();
			breaker.decrement();
			if(block.eof){
				if(logger.isTraceEnabled()){
					logger.trace(signature + ": eof detected, closing stream");
				}
				eof = true;   // EOF 検出
				return Optional.empty();
			} else {
				if(logger.isTraceEnabled()){
					logger.trace(signature + ": dequeue block message for stream: " + block);
				}
				processing = Optional.of(block.toByteBuffer());
				return processing;
			}
		} catch(InterruptedException ex){
			throw new IOException("operation interrupted", ex);
		}
	}
	private void ensureOpen() throws IOException{
		if(closed){
			throw new IOException("stream closed");
		}
	}
}

