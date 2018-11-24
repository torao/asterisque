package io.asterisque.core.wire.netty;

import io.asterisque.core.codec.CodecException;
import io.asterisque.core.codec.MessageCodec;
import io.asterisque.core.msg.Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

/**
 * TCP/IP 上のプレーンなバイナリストリームとメッセージとの変換を行う Netty エンコーダ/デコーダです。
 */
interface TCP {

  /**
   * メッセージをプレーンなバイナリストリームに変換するための Netty エンコーダです。
   */
  class Encoder extends MessageToByteEncoder<Message> {
    public void encode(ChannelHandlerContext ctx, Message msg, ByteBuf b) throws CodecException {
      b.writeBytes(MessageCodec.MessagePackCodec.encode(msg));
    }
  }

  /**
   * プレーンなバイナリストリームからメッセージを復元するための Netty デコーダです。
   */
  class Decoder extends ByteToMessageDecoder {
    public void decode(ChannelHandlerContext ctx, ByteBuf b, List<Object> out) throws CodecException {
      ByteBuffer buffer = b.nioBuffer();
      Optional<Message> msg = MessageCodec.MessagePackCodec.decode(buffer);
      msg.ifPresent(m -> {
        b.skipBytes(buffer.position());
        out.add(msg.get());
      });
    }
  }

}
