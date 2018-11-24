/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.netty;

import io.asterisque.core.codec.CodecException;
import io.asterisque.core.codec.MessageCodec;
import io.asterisque.core.msg.Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// MessageDecoder
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * Netty のデコーダーです。
 *
 * @author Takami Torao
 */
class MessageDecoder extends ByteToMessageDecoder {
  public final MessageCodec codec;
  public MessageDecoder(MessageCodec codec){
    this.codec = codec;
  }
  public void decode(ChannelHandlerContext ctx, ByteBuf b, List<Object> out) throws CodecException {
    ByteBuffer buffer = b.nioBuffer();
    Optional<Message> msg = codec.decode(buffer);
    if(msg.isPresent()){
      b.skipBytes(buffer.position());
      out.add(msg.get());
    }
  }
}
