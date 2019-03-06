package io.asterisque.wire.rpc

import java.io.InputStream

import io.asterisque.wire.gateway.MessageQueue
import io.asterisque.wire.message.Message.{Block, Open}
import javax.annotation.Nonnull
import org.slf4j.{Logger, LoggerFactory}

/**
  * [[Block]] の iteration および streaming を行うことのできるパイプです。
  *
  * DoS 攻撃を回避するため、アプリケーションが明示的にブロック受信を許可しない限りブロックの受信は行いません。このクラスは
  * ブロックの受信を許可した function に対して生成されます。
  *
  * @param open             パイプ生成に使用した Open メッセージ
  * @param stub             スタブ
  * @param cooperativeLimit バッファ上限
  */
class StreamPipe private[rpc](@Nonnull open:Open, @Nonnull stub:Pipe.Stub, cooperativeLimit:Int) extends Pipe(open, stub) {

  /**
    * このパイプにが受信したブロックを参照するためのキュー。
    */
  private[this] val blockQueue = new MessageQueue(super.toString, cooperativeLimit)

  /**
    * このパイプに対してブロックが到着した時に呼び出されます。
    *
    * @param block 受信したブロック
    */
  private[rpc] def dispatchBlock(@Nonnull block:Block):Unit = {
    blockQueue.offer(block)
    if(block.eof) blockQueue.close()
  }

  /**
    * メッセージングやストリーミングのバイナリメッセージを同期処理で受信するための iterator を参照します。
    *
    * @return バイナリメッセージの iterator
    */
  @Nonnull
  def iterate():Iterator[Array[Byte]] = blockQueue.iterator.collect { case block:Block => block.payload }

  /**
    * このパイプがメッセージとして受信したバイナリデータを [[InputStream]] として参照します。
    */
  @Nonnull
  lazy val in:InputStream = {
    val it = iterate()
    new InputStream() {
      private[this] var buffer:Array[Byte] = _
      private[this] var offset:Int = 0

      override def read:Int = if(eof) -1 else {
        val b:Byte = buffer(offset)
        offset += 1
        b & 0xFF
      }

      override def read(b:Array[Byte]):Int = read(b, 0, b.length)

      override def read(b:Array[Byte], o:Int, l:Int):Int = if(eof) -1 else {
        val len:Int = Math.min(l, buffer.length - offset)
        System.arraycopy(buffer, offset, b, o, len)
        offset += len
        len
      }

      private def eof:Boolean = {
        while(buffer == null || offset == buffer.length) {
          offset = 0
          if(it.hasNext) {
            buffer = it.next
          } else {
            buffer = null
            return true
          }
        }
        false
      }
    }
  }
}

object StreamPipe {
  private[StreamPipe] val logger:Logger = LoggerFactory.getLogger(classOf[StreamPipe])

  /*

  /**
    * パイプ上で受信した Block メッセージのバイナリデータをストリームとして参照するためのクラス。
    *
    * キューとしての役割もあります。
    */
  private[Pipe] class InputStream(val breaker:CircuitBreaker, val maxQueueSize:Int, val signature:String) extends JInputStream with (Block => Unit) {

    /**
      * パイプから受信した Block メッセージを保持するためのキュー。
      */
    private[this] val receiveQueue = new LinkedBlockingQueue[Block]

    /**
      * 読み出し中の ByteBuffer。
      */
    private[this] val buffer = new AtomicReference[ByteBuffer]()

    /**
      * ストリーム上で EOF を検知したかのフラグ。
      */
    private[this] var eof = false

    /**
      * インスタンスの利用側によってクローズされたかのフラグ。
      */
    private[this] var closed = false

    def isClosed:Boolean = closed

    override def apply(block:Block):Unit = {
      if(!closed) {
        if(logger.isTraceEnabled) {
          logger.trace(s"$signature: apply($block) enqueueing specified block message as stream data")
        }
        receiveQueue.add(block)
        breaker.increment()
        val size = receiveQueue.size
        if(size > maxQueueSize && !block.eof) {
          logger.error(s"$signature: queue for pipe overflow; $size / $maxQueueSize")
          close()
        }
      }
    }

    @throws[IOException]
    override def read:Int = {
      val buffer = processingBuffer()
      if(buffer != null) {
        ensureOpen()
        buffer.get & 0xFF
      } else -1
    }

    @throws[IOException]
    override def read(b:Array[Byte]):Int = read(b, 0, b.length)

    @throws[IOException]
    override def read(b:Array[Byte], offset:Int, length:Int):Int = {
      val buffer = processingBuffer()
      if(buffer != null) {
        ensureOpen()
        val len = Math.min(length, buffer.remaining)
        buffer.get(b, offset, len)
        len
      } else -1
    }

    override def close():Unit = {
      closed = true
      logger.trace(signature + ": lock()")
    }

    @Nullable
    @throws[IOException]
    private[this] def processingBuffer():ByteBuffer = if(eof) {
      null
    } else {
      val buf = buffer.get()
      if(buf != null && buf.hasRemaining) {
        buf
      } else if(receiveQueue.isEmpty && closed) {
        // EOF 到着前に何らかの理由でクローズされた (キューのオーバーフローなど)
        throw new IOException("stream was closed")
      } else try {
        val block = receiveQueue.take()
        breaker.decrement()
        if(block.eof) {
          if(logger.isTraceEnabled) {
            logger.trace(signature + ": eof detected, wsClosed stream")
          }
          eof = true // EOF 検出
        }
        if(logger.isTraceEnabled) {
          logger.trace(s"$signature: dequeue block message for stream: $block")
        }
        buffer.set(if(eof && block.length == 0) null else block.toByteBuffer)
        buffer.get()
      } catch {
        case ex:InterruptedException =>
          throw new IOException("operation interrupted", ex)
      }
    }

    @throws[IOException]
    private def ensureOpen():Unit = if(closed) {
      throw new IOException("stream was closed")
    }
  }

  */
}
