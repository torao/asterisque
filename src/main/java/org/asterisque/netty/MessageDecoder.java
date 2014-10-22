/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.netty;

import org.asterisque.msg.Message;
import org.asterisque.codec.Codec;
import org.asterisque.codec.CodecException;
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
	public final Codec codec;
	public MessageDecoder(Codec codec){
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
