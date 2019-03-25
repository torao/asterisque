import java.io.ByteArrayOutputStream
import java.net.{InetSocketAddress, Socket}
import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, Selector, SocketChannel}
import java.nio.charset.StandardCharsets._

import javax.net.ssl.SSLContext

val context = SSLContext.getDefault
val engine = context.createSSLEngine("www.google.com", 441)

val selector = Selector.open()
val channel = SocketChannel.open(new InetSocketAddress("www.google.com", 441))
channel.configureBlocking(false)

val outputMessage = ByteBuffer.wrap("GET / HTTP/1.0\r\nConnection:close\r\n\r\n".getBytes(US_ASCII))
val buffer = ByteBuffer.allocate(1024)
val buffer2 = ByteBuffer.allocate(1024)
val inputMessage = new ByteArrayOutputStream()
channel.register(selector, SelectionKey.OP_WRITE | SelectionKey.OP_READ)
engine.beginHandshake()
while(channel.isOpen){
  selector.select { key =>
    if(key.isWritable && outputMessage.remaining() > 0){
      buffer.clear()
      engine.wrap(outputMessage, buffer)
      channel.write(buffer)
      if(outputMessage.remaining() == 0){
        key.interestOps(SelectionKey.OP_READ)
      }
    } else if(key.isReadable){
      buffer.clear()
      channel.read(buffer)
      buffer.flip()
      buffer2.clear()
      engine.unwrap(buffer, buffer2)
      inputMessage.write(buffer2.array(), buffer2.arrayOffset(), buffer2.remaining())
      println(new String(inputMessage.toByteArray, US_ASCII))
    } else if(!key.isValid){
      channel.close()
      selector.close()
      println(new String(inputMessage.toByteArray, US_ASCII))
    }
  }
}

