package io.asterisque.core;

import io.asterisque.core.msg.Block;

import java.nio.ByteBuffer;

/**
 * Pipe からブロックデータを送信するためのインターフェースです。
 */
public final class PipeMessageSink {
  private final Pipe pipe;

  PipeMessageSink(Pipe pipe) { this.pipe = pipe; }

  // ============================================================================================
  // ブロックの送信
  // ============================================================================================
  /**
   * メッセージの終了を示す EOF メッセージを非同期メッセージとして送信します。
   */
  public PipeMessageSink sendEOF() {
    pipe.block(Block.eof(pipe.id()));
    return this;
  }

  // ============================================================================================
  // ブロックの送信
  // ============================================================================================
  /**
   * 指定されたバイナリデータを非同期メッセージとして送信します。
   * このメソッドは渡された buffer をそのまま送信バッファとして使用します。従ってメソッド呼び出しが終わった後に
   * バッファの内容を変更した場合、実際に送信されるデータは保証されません。
   */
  public PipeMessageSink sendDirect(byte[] buffer) {
    sendDirect(buffer, 0, buffer.length);
    return this;
  }

  // ============================================================================================
  // ブロックの送信
  // ============================================================================================
  /**
   * 指定されたバイナリデータを非同期メッセージとして送信します。
   * このメソッドは渡された buffer をそのまま送信バッファとして使用します。従ってメソッド呼び出しが終わった後に
   * バッファの内容を変更した場合、実際に送信されるデータは保証されません。
   */
  public PipeMessageSink sendDirect(byte[] buffer, int offset, int length) {
    pipe.block(buffer, offset, length);
    return this;
  }

  // ============================================================================================
  // ブロックの送信
  // ============================================================================================
  /**
   * 指定されたバイナリデータを非同期メッセージとして送信します。
   */
  public PipeMessageSink send(ByteBuffer buffer) {
    byte[] payload = new byte[buffer.remaining()];
    buffer.get(payload);
    sendDirect(payload);
    return this;
  }

  // ============================================================================================
  // ブロックの送信
  // ============================================================================================
  /**
   * 指定されたバイナリデータを非同期メッセージとして送信します。
   */
  public PipeMessageSink send(byte[] buffer) {
    send(buffer, 0, buffer.length);
    return this;
  }

  // ============================================================================================
  // ブロックの送信
  // ============================================================================================
  /**
   * 指定されたバイナリデータを非同期メッセージとして送信します。
   */
  public PipeMessageSink send(byte[] buffer, int offset, int length) {
    byte[] payload = new byte[length];
    System.arraycopy(buffer, offset, payload, 0, length);
    sendDirect(payload, 0, length);
    return this;
  }

}
