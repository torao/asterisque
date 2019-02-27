/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.core;

import io.asterisque.core.msg.Block;
import io.asterisque.core.util.Latch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// PipeOutputStream
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

/**
 * パイプに対してバイナリデータを Block メッセージとして送信するための出力ストリームです。出力されたバイナリは内部
 * でバッファリングされ、フラグメント化されたブロックとして送信されます。
 * バッファ容量に達した場合やフラッシュされた場合はバッファの内容を Block メッセージにカプセル化して非同期で
 * セッションへ送信します。
 */
class PipeOutputStream extends OutputStream {
  private static final Logger logger = LoggerFactory.getLogger(PipeOutputStream.class);
  private final Pipe pipe;
  private ByteBuffer buffer;
  private boolean closed = false;
  private final Latch barrier;

  public PipeOutputStream(Pipe pipe, Latch barrier, int bufferSize){
    this.pipe = pipe;
    this.buffer = ByteBuffer.allocate(bufferSize);
    this.barrier = barrier;
  }

  public PipeOutputStream(Pipe pipe, Latch barrier){
    this(pipe, barrier, 4 * 1024);
  }

  /**
   * 出力バッファサイズを変更します。バッファリングしているデータはすべて [[flush()]] されます。
   * @param size 出力バッファサイズ
   */
  public void setBufferSize(int size) throws IOException  {
    flush();
    assert(buffer.position() == 0);
    buffer = ByteBuffer.allocate(size);
  }

  public synchronized void write(int b) throws IOException {
    ensureWrite(1);
    write(() -> buffer.put((byte) b));
  }
  @Override
  public void write(byte[] b) throws IOException {
    write(b, 0, b.length);
  }
  @Override
  public synchronized void write(byte[] b, int offset, int length) throws IOException {
    ensureWrite(length);
    write(() -> buffer.put(b, offset, length));
  }
  private void write(Runnable exec) throws IOException {
    try {
      barrier.exec(exec);
    } catch(InterruptedException ex){
      throw new IOException("write operation interrupted", ex);
    }
  }
  private void ensureWrite(int len) throws IOException {
    if(closed){
//      throw new IOException(pipe + ": unable to write to wsClosed pipe or stream: " + pipe.id);
    }
    // TODO バッファサイズより大きなデータが書き込めない?
    if(buffer.position() + len > buffer.capacity()){
      flush();
    }
  }
  @Override
  public void flush() {
    if(logger.isTraceEnabled()){
      logger.trace(pipe + ": flush()");
    }
    if(buffer.position() > 0){
      buffer.flip();
      while(buffer.hasRemaining()){
        int len = Math.min(buffer.remaining(), Block.MaxPayloadSize);
        byte[] b = new byte[len];
        buffer.get(b, 0, b.length);
//        pipe.sink.send(b);
      }
      buffer.clear();
    }
  }
  @Override
  public void close() {
    if(!closed){
      flush();
//      pipe.sink.sendEOF();
      closed = true;
      logger.trace(pipe + ": lock()");
    }
  }
  public void emergencyClose() {
    closed = true;
    logger.trace(pipe + ": output stream wsClosed by peer");
  }
}
