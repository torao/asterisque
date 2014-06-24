/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.codec;

import org.asterisque.Asterisque;
import org.asterisque.msg.*;

import java.nio.ByteBuffer;
import java.util.*;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Codec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * メッセージのシリアライズとデシリアライズを行うためのクラスです。
 * アプリケーションインターフェースで使用されているパラメータのシリアライズ/デシリアライズは Marshal と Unmarshal
 * によってサブクラスの実装に委譲されています。
 *
 * @author Takami Torao
 */
public interface Codec {

	// ==============================================================================================
	// 最大メッセージ長
	// ==============================================================================================
	/**
	 * シリアライズした 1 メッセージの最大バイナリ長です。IPv4 のデータ部最大長である 65,507 を表します。
	 */
	public static int MaxMessageSize = 65507;

	// ==============================================================================================
	// メッセージのシリアライズ
	// ==============================================================================================
	/**
	 * 指定されたメッセージをサブクラスが実装する形式でシリアライズします。
	 * 変換後のバイナリサイズが {@link #MaxMessageSize} を超える場合には {@link org.asterisque.codec.CodecException}
	 * が発生します。
	 * @throws org.asterisque.codec.CodecException シリアライズに失敗した場合
	 */
	public default ByteBuffer encode(Message msg) throws CodecException{
		assert(msg != null);
		Marshal m = newMarshal();
		if(msg instanceof Open) {
			Open open = (Open)msg;
			m.writeTag(Msg.Open);
			m.writeInt16(open.pipeId);
			m.writeInt8(open.priority);
			m.writeInt16(open.functionId);
			m.writeList(Arrays.asList(open.params));
		} else if(msg instanceof Close) {
			Close close = (Close)msg;
			m.writeTag(Msg.Close);
			m.writeInt16(close.pipeId);
			if(close.abort.isPresent()){
				m.writeFalse();
				m.writeInt32(close.abort.get().code);
				m.writeString(close.abort.get().message);
			} else {
				m.writeTrue();
				m.write(close.result.get());
			}
		} else if(msg instanceof Block) {
			Block block = (Block)msg;
			byte status = (byte)((block.eof? 0: 1) | ((block.lossy? 0: 1) << 1));
			m.writeTag(Msg.Block);
			m.writeInt16(block.pipeId);
			m.writeInt8(status);
			if(! block.eof) {
				if(block.length > Block.MaxPayloadSize){
					throw new CodecException(String.format("block length too large: %d / %d", block.length, Block.MaxPayloadSize));
				}
				m.writeBinary(block.payload, block.offset, block.length);
			}
		} else if(msg instanceof Control) {
			Control control = (Control) msg;
			m.writeTag(Msg.Control);
			m.writeInt8(control.code);
			m.writeBinary(control.data);
		} else {
			throw new IllegalStateException(String.format("unexpected message type: %s", msg.getClass().getName()));
		}
		ByteBuffer b = m.toByteBuffer();
		assert(b.remaining() > 0);
		if(b.remaining() > MaxMessageSize){
			throw new CodecException(String.format("message binary too large: %d > %d: %s", b.remaining(), MaxMessageSize, msg));
		}
		return b;
	}

	// ==============================================================================================
	// メッセージのデシリアライズ
	// ==============================================================================================
	/**
	 * 指定されたメッセージをデコードします。
	 *
	 * このメソッドの呼び出しはデータを受信する都度行われます。従って、サブクラスはメッセージ全体を復元できるだけデー
	 * タを受信していない場合に None を返す必要があります。
	 *
	 * パラメータの [[java.nio.ByteBuffer]] の位置は次回の呼び出しまで維持されます。このためサブクラスは復元した
	 * メッセージの次の適切な読み出し位置を正しくポイントする必要があります。またメッセージを復元できるだけのデータを
	 * 受信していない場合には読み出し位置を変更すべきではありません。コーデック実装により無視できるバイナリが存在する
	 * 場合はバッファ位置を変更して None を返す事が出来ます。
	 *
	 * メッセージのデコードに失敗した場合は [[com.kazzla.asterisk.codec.CodecException]] が発生します。
	 *
	 * @param buffer デコードするメッセージ
	 * @return デコードしたメッセージ
	 */
	public default Optional<Message> decode(ByteBuffer buffer){
		int pos = buffer.position();
		try {
			Unmarshal u = newUnmarshal(buffer);
			byte tag = u.readTag();
			switch(tag){
				case Msg.Open:
					short pipeId1 = u.readInt16();
					byte priority = u.readInt8();
					short functionId = u.readInt16();
					List<?> params = u.readList();
					return Optional.of(new Open(pipeId1, priority, functionId, params.toArray()));
				case Msg.Close:
					short pipeId2 = u.readInt16();
					boolean success = u.readBoolean();
					if(success){
						Object result = u.read();
						return Optional.of(new Close(pipeId2, result));
					} else {
						int code = u.readInt32();
						String msg = u.readString();
						return Optional.of(new Close(pipeId2, new Abort(code, msg)));
					}
				case Msg.Block:
					short pipeId3 = u.readInt16();
					byte status = u.readInt8();
					boolean eof = (status & 1) != 0;
					boolean lossy = (status & (1 << 1)) != 0;
					if(! eof){
						byte[] payload = u.readBinary();
						return Optional.of(new Block(pipeId3, lossy, payload, 0, payload.length));
					} else {
						return Optional.of(Block.eof(pipeId3));
					}
				case Msg.Control:
					byte code = u.readInt8();
					byte[] data = u.readBinary();
					return Optional.of(new Control(code, data));
				default:
					throw new CodecException(String.format("unexpected message type: %02X", tag & 0xFF));
			}
		} catch(Unsatisfied ex){
			buffer.position(pos);
			return Optional.empty();
		}
	}

	// ==============================================================================================
	// 直列化処理の取得
	// ==============================================================================================
	/**
	 * 直列化処理を参照します。
	 */
	public Marshal newMarshal();

	// ==============================================================================================
	// 非直列化処理の取得
	// ==============================================================================================
	/**
	 * 非直列化処理を参照します。
	 */
	public Unmarshal newUnmarshal(ByteBuffer buffer);

	public interface Msg {
		public final byte Control = '*';
		public final byte Open = 'O';
		public final byte Close = 'C';
		public final byte Block = 'B';
	}

	public interface Tag {
		public final byte Null = 0;
		public final byte True = 1;
		public final byte False = 2;
		public final byte Int8 = 3;
		public final byte Int16 = 4;
		public final byte Int32 = 5;
		public final byte Int64 = 6;
		public final byte Float32 = 7;
		public final byte Float64 = 8;
		public final byte Binary = 10;
		public final byte String = 11;
		public final byte UUID = 12;

		public final byte List = 32;
		public final byte Map = 33;
		public final byte Struct = 34;
	}

	public interface Marshal {
		public ByteBuffer toByteBuffer();
		public default void writeTag(byte tag){ writeInt8(tag); }
		public default void writeTrue(){ writeTag(Tag.True); }
		public default void writeFalse(){ writeTag(Tag.False); }
		public void writeInt8(byte i);
		public default void writeUInt8(short i){
			if(i < 0 || i > 0xFF){
				throw new IllegalArgumentException(String.format("out of unsigned int16 range: %d", i));
			}
			writeInt8((byte) (i & 0xFF));
		}
		public void writeInt16(short i);
		public default void writeUInt16(int i){
			if(i < 0 || i > 0xFFFF){
				throw new IllegalArgumentException(String.format("out of unsigned int16 range: %d", i));
			}
			writeInt16((short)(i & 0xFFFF));
		}
		public void writeInt32(int i);
		public void writeInt64(long i);
		public void writeFloat32(float i);
		public void writeFloat64(double i);
		public default void writeBinary(byte[] b){ writeBinary(b, 0, b.length); }
		public void writeBinary(byte[] b, int offset, int length);
		public default void writeString(String s) {
			writeBinary(s.getBytes(Asterisque.UTF8));
		}
		public default void writeUUID(UUID u) {
			writeInt64(u.getMostSignificantBits());
			writeInt64(u.getLeastSignificantBits());
		}
		public default void writeList(List<?> l){
			writeUInt16(l.size());
			for(Object x: l){
				write(x);
			}
		}
		public default void writeMap(Map<?,?> m){
			writeUInt16(m.size());
			for(Map.Entry<?,?> e: m.entrySet()){
				write(e.getKey());
				write(e.getValue());
			}
		}
		public default void writeStruct(Struct b){
			if(b.count() > Struct.MaxFields){
				throw new IllegalArgumentException(
					"field count of " + b.getClass().getName() + " is too large: " + b.count() + " / " + Struct.MaxFields);
			}
			writeString(b.schema());
			writeUInt8((short) (b.count() & 0xFF));
			for(int i=0; i<b.count(); i++){
				write(b.valueAt(i));
			}
		}
		// ============================================================================================
		/**
		 * Asterisque がサポートする任意のデータを {@link org.asterisque.codec.Codec.Tag タグ} 付きで書き込み
		 * ます。このメソッドは {@link org.asterisque.codec.Codec.Unmarshal#read()} の対になるメソッドです。
		 */
		public default void write(Object value){
			if(value == null) {
				writeTag(Tag.Null);
			} else if(value instanceof Boolean){
				if((Boolean)value) {
					writeTag(Tag.True);
				} else {
					writeTag(Tag.False);
				}
			} else if(value instanceof Byte){
				writeTag(Tag.Int8);
				writeInt8((Byte)value);
			} else if(value instanceof Short){
				writeTag(Tag.Int16);
				writeInt16((Short)value);
			} else if(value instanceof Integer){
				writeTag(Tag.Int32);
				writeInt32((Integer)value);
			} else if(value instanceof Long){
				writeTag(Tag.Int64);
				writeInt64((Long)value);
			} else if(value instanceof Float){
				writeTag(Tag.Float32);
				writeFloat32((Float)value);
			} else if(value instanceof Double){
				writeTag(Tag.Float64);
				writeFloat64((Double)value);
			} else if(value instanceof byte[]){
				writeTag(Tag.Binary);
				writeBinary((byte[])value);
			} else if(value instanceof String){
				writeTag(Tag.String);
				writeString((String)value);
			} else if(value instanceof UUID){
				writeTag(Tag.UUID);
				writeUUID((UUID)value);
			} else if(value instanceof List<?>){
				writeTag(Tag.List);
				writeList((List<?>)value);
			} else if(value instanceof Map<?,?>){
				writeTag(Tag.Map);
				writeMap((Map<?,?>)value);
			} else if(value instanceof Struct){
				writeTag(Tag.Struct);
				writeStruct((Struct)value);
			} else {
				throw new CodecException(String.format("marshal not supported for data type: %s", value.getClass().getName()));
			}
		}
	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// Unmarshal
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * Asterisque がサポートするデータ型をデコードします。
	 * <p>
	 * 各メソッドはデータの完全セットが到着したかどうかに関わらずデータを受信するたびに復元が試行されます。このため、
	 * 実装クラスは対象データを復元できるだけのバイナリを受信していない場合に {@link org.asterisque.codec.Codec.Unsatisfied}
	 * を throw する必要があります。
	 * <p>
	 * 正常にデータを復元できた場合にバイナリのどこまでが読み込み済みなのかを知らせるため、実装クラスは
	 * {@link #newUnmarshal(java.nio.ByteBuffer)} に渡された {@link java.nio.ByteBuffer} のバッファ位置を
	 * 次の読み込み位置まで正確に移動させる必要があります。データを復元できるだけのバイナリを受信しておらず
	 * {@link org.asterisque.codec.Codec.Unsatisfied} が throw される場合は呼び出し側で位置のリセットが行われ
	 * るため、サブクラス側でバッファ位置を復元する必要はありません。
	 * <p>
	 * 各メソッド Unsatisfied の他にメッセージのデコードに失敗した場合は {@link org.asterisque.codec.CodecException}
	 * が発生します。
	 *
	 * @see #newUnmarshal(java.nio.ByteBuffer)
	 */
	public interface Unmarshal {
		public default byte readTag() throws Unsatisfied { return readInt8(); }
		public default boolean readBoolean() throws Unsatisfied{
			byte tag = readTag();
			switch(tag){
				case Tag.True:
					return true;
				case Tag.False:
					return false;
				default:
					throw new CodecException(String.format("unexpected boolean value: 0x%02X", tag & 0xFF));
			}
		}
		public byte readInt8() throws Unsatisfied;
		public default short readUInt8() throws Unsatisfied {
			return (short)(readInt8() & 0xFF);
		}
		public short readInt16() throws Unsatisfied;
		public default int readUInt16() throws Unsatisfied {
			return readInt16() & 0xFFFF;
		}
		public int readInt32() throws Unsatisfied;
		public long readInt64() throws Unsatisfied;
		public float readFloat32() throws Unsatisfied;
		public double readFloat64() throws Unsatisfied;
		public byte[] readBinary() throws Unsatisfied;
		public default String readString() throws Unsatisfied {
			return new String(readBinary(), Asterisque.UTF8);
		}
		public default UUID readUUID() throws Unsatisfied {
			long m = readInt64();
			long l = readInt64();
			return new UUID(m, l);
		}
		public default List<?> readList() throws Unsatisfied {
			int length = readUInt16();
			List<Object> l = new ArrayList<>(length);
			for(int i=0; i<length; i++){
				l.add(read());
			}
			return l;
		}
		public default Map<?,?> readMap() throws Unsatisfied {
			int length = readUInt16();
			Map<Object, Object> m = new HashMap<>(length);
			for(int i=0; i<length; i++){
				Object key = read();
				Object value = read();
				m.put(key, value);
			}
			return m;
		}
		public default Struct readStruct() throws Unsatisfied{
			String schema = readString();
			int length = readUInt8();
			Object[] values = new Object[length];
			for(int i=0; i<length; i++){
				values[i] = read();
			}
			return new Struct() {
				@Override public String schema() { return schema; }
				@Override public int count() { return length; }
				@Override public Object valueAt(int i) { return values[i]; }
			};
		}
		// ============================================================================================
		/**
		 * 先行する {@link org.asterisque.codec.Codec.Tag タグ} で識別される、Asterisque がサポートする任意の
		 * データを読み込みます。このメソッドは {@link org.asterisque.codec.Codec.Marshal#write(Object)} の
		 * 対になるメソッドです。
		 */
		public default Object read() throws Unsatisfied{
			byte tag = readTag();
			switch(tag){
				case Tag.Null:
					return Optional.of(null);
				case Tag.True:
					return Optional.of(Boolean.TRUE);
				case Tag.False:
					return Optional.of(Boolean.FALSE);
				case Tag.Int8:
					return readInt8();
				case Tag.Int16:
					return readInt16();
				case Tag.Int32:
					return readInt32();
				case Tag.Int64:
					return readInt64();
				case Tag.Float32:
					return readFloat32();
				case Tag.Float64:
					return readFloat64();
				case Tag.Binary:
					return readBinary();
				case Tag.String:
					return readString();
				case Tag.UUID:
					return readUUID();
				case Tag.List:
					return readList();
				case Tag.Map:
					return readMap();
				case Tag.Struct:
					return readStruct();
				default:
					throw new CodecException(String.format("unexpected value tag: %02X", tag & 0xFF));
			}
		}
	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// Unsatisfied
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * 読み込まれているバイナリがデータを復元するために足りない場合に {@link org.asterisque.codec.Codec.Unmarshal}
	 * クラスで発生します。データを復元するためにさらなるバイナリを読み出してデータ復元を再実行する必要があります。
	 */
	public static class Unsatisfied extends Exception{ }

}
