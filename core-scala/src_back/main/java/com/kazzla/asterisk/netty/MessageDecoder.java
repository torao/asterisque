/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.netty;

import com.kazzla.asterisk.Message;
import com.kazzla.asterisk.codec.Codec;
import com.kazzla.asterisk.codec.CodecException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import scala.Option;
import scala.Some;

import java.nio.ByteBuffer;
import java.util.List;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// MessageDecoder
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class MessageDecoder extends ByteToMessageDecoder {
  public final Codec codec;
  public MessageDecoder(Codec codec){
    this.codec = codec;
  }
  public void decode(ChannelHandlerContext ctx, ByteBuf b, List<Object> out) throws CodecException {
    ByteBuffer buffer = b.nioBuffer();
    Option<Message> msg = codec.decode(buffer);
    if(msg instanceof Some){
      b.skipBytes(buffer.position());
      out.add(msg);
    }
  }
}
