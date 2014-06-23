/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.netty;

import org.asterisque.message.Message;
import org.asterisque.codec.Codec;
import org.asterisque.codec.CodecException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// MessageEncoder
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class MessageEncoder extends MessageToByteEncoder<Message> {
	public final Codec codec;
	public MessageEncoder(Codec codec){
		this.codec = codec;
	}
	public void encode(ChannelHandlerContext ctx, Message msg, ByteBuf b) throws CodecException {
		b.writeBytes(codec.encode(msg));
	}
}

