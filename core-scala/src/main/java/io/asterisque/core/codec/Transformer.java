package io.asterisque.codec;


import io.asterisque.msg.*;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;

interface Transformer<T extends Message> {
  void serialize(@Nonnull Marshal m, @Nonnull T message);

  T deserialize(@Nonnull Unmarshal u) throws StandardCodec.Unsatisfied;

  Transformer<io.asterisque.msg.Open> Open = new Transformer<Open>() {
    @Override
    public void serialize(@Nonnull Marshal m, @Nonnull Open open) {
      m.writeInt16(open.pipeId);
      m.writeInt8(open.priority);
      m.writeString(open.serviceId);
      m.writeInt16(open.functionId);
      m.writeList(Arrays.asList(open.params));
    }

    @Override
    public Open deserialize(@Nonnull Unmarshal u) throws StandardCodec.Unsatisfied {
      short pipeId = u.readInt16();
      byte priority = u.readInt8();
      String serviceId = u.readString();
      short functionId = u.readInt16();
      List<?> params = u.readList();
      return new Open(pipeId, priority, serviceId, functionId, params.toArray());
    }
  };

  Transformer<io.asterisque.msg.Close> Close = new Transformer<Close>() {
    @Override
    public void serialize(@Nonnull Marshal m, @Nonnull Close close) {
      m.writeInt16(close.pipeId);
      if (close.abort != null) {
        m.writeFalse();
        m.writeInt32(close.abort.code);
        m.writeString(close.abort.message);
      } else {
        m.writeTrue();
        m.write(close.result);
      }
    }

    @Override
    public Close deserialize(@Nonnull Unmarshal u) throws StandardCodec.Unsatisfied {
      short pipeId = u.readInt16();
      boolean success = u.readBoolean();
      if (success) {
        Object result = u.read();
        return new Close(pipeId, result);
      } else {
        int code = u.readInt32();
        String msg = u.readString();
        return new Close(pipeId, new Abort(code, msg));
      }
    }
  };

  Transformer<io.asterisque.msg.Block> Block = new Transformer<io.asterisque.msg.Block>() {
    @Override
    public void serialize(@Nonnull Marshal m, @Nonnull io.asterisque.msg.Block block) {
      byte status = (byte) (block.eof ? (1 << 7) : block.loss);
      assert (block.loss >= 0);
      m.writeInt16(block.pipeId);
      m.writeInt8(status);
      if (!block.eof) {
        if (block.length > io.asterisque.msg.Block.MaxPayloadSize) {
          throw new CodecException(String.format("block payload length too large: %d / %d", block.length, io.asterisque.msg.Block.MaxPayloadSize));
        }
        m.writeBinary(block.payload, block.offset, block.length);
      }
    }

    @Override
    public io.asterisque.msg.Block deserialize(@Nonnull Unmarshal u) throws StandardCodec.Unsatisfied {
      short pipeId3 = u.readInt16();
      byte status = u.readInt8();
      boolean eof = status == (byte) (1 << 7);
      byte loss = (byte) (eof ? 0 : status & 0x7F);
      if (!eof) {
        byte[] payload = u.readBinary();
        return new Block(pipeId3, loss, payload, 0, payload.length);
      } else {
        return io.asterisque.msg.Block.eof(pipeId3);
      }
    }
  };

  Transformer<Control> Control = new Transformer<io.asterisque.msg.Control>() {
    @Override
    public void serialize(@Nonnull Marshal m, @Nonnull io.asterisque.msg.Control control) {
      m.writeInt8(control.code);
      m.writeBinary(control.data);
    }

    @Override
    public io.asterisque.msg.Control deserialize(@Nonnull Unmarshal u) throws StandardCodec.Unsatisfied {
      byte code = u.readInt8();
      byte[] data = u.readBinary();
      return new Control(code, data);
    }
  };
}
