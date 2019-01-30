package io.asterisque.core.session;

import io.asterisque.core.codec.VariableCodec;
import io.asterisque.core.msg.Block;
import io.asterisque.core.msg.Message;
import io.asterisque.core.msg.Open;
import io.asterisque.core.wire.MessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.InputStream;
import java.util.Iterator;

/**
 * {@link Block} の iteration を行うことのできるパイプです。
 */
public class StreamPipe extends Pipe {
  private static final Logger logger = LoggerFactory.getLogger(StreamPipe.class);

  /**
   * このパイプに対して到着したブロックを参照するためのキュー。
   */
  @Nonnull
  private final MessageQueue blockQueue;

  /**
   * @param open             パイプ生成に使用した Open メッセージ
   * @param stub             スタブ
   * @param cooperativeLimit バッファ上限
   */
  StreamPipe(@Nonnull VariableCodec codec, @Nonnull Open open, @Nonnull Stub stub, int cooperativeLimit) {
    super(codec, open, stub);
    this.blockQueue = new MessageQueue(super.toString(), cooperativeLimit);
  }

  /**
   * このパイプに対してブロックが到着した時に呼び出されます。
   *
   * @param block 受信したブロック
   */
  void dispatchBlock(@Nonnull Block block) {
    blockQueue.offer(block);
    if (block.eof) {
      blockQueue.close();
    }
  }

  /**
   * メッセージングやストリーミングのバイナリメッセージを同期処理で受信するための iterator を参照します。
   *
   * @return バイナリメッセージの iterator
   */
  @Nonnull
  public Iterator<byte[]> iterator() {
    Iterator<Message> it = blockQueue.iterator();
    return new Iterator<byte[]>() {
      @Override
      public boolean hasNext() {
        return it.hasNext();
      }

      @Override
      public byte[] next() {
        return ((Block) it.next()).payload;
      }
    };
  }

  /**
   * このパイプがメッセージとして受信したバイナリデータを `InputStream` として参照します。
   * 非同期で到着するメッセージを同期ストリームで参照するために、入力ストリームを使用する前に [[useInputStream()]]
   * を呼び出して内部的なキューを準備しておく必要があります。[[useInputStream()]] を行わず入力ストリームを参照
   * した場合は例外となります。
   */
  @Nonnull
  public InputStream in() {
    Iterator<byte[]> it = iterator();
    return new InputStream() {
      private byte[] buffer;
      private int offset = 0;

      @Override
      public int read() {
        if (eof()) {
          return -1;
        }
        byte b = buffer[offset];
        offset++;
        return b & 0xFF;
      }

      @Override
      public int read(byte[] b) {
        return read(b, 0, b.length);
      }

      @Override
      public int read(byte[] b, int o, int l) {
        if (eof()) {
          return -1;
        }
        int len = Math.min(l, buffer.length - offset);
        System.arraycopy(buffer, offset, b, o, len);
        offset += len;
        return len;
      }

      private boolean eof() {
        while (buffer == null || offset == buffer.length) {
          offset = 0;
          if (it.hasNext()) {
            buffer = it.next();
          } else {
            buffer = null;
            return true;
          }
        }
        return false;
      }
    };
  }

}
