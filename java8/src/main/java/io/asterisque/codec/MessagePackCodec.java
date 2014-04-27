/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.codec;

import org.msgpack.MessagePack;
import org.msgpack.packer.BufferPacker;
import org.msgpack.unpacker.BufferUnpacker;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// MessagePackCodec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

/**
 * {@link java.nio.ByteBuffer} を使用した非圧縮形式のメッセージシリアライゼイションを行います。
 * バイト順序は {@link java.nio.ByteOrder#BIG_ENDIAN ビッグエンディアン} となります。
 *
 * @author Takami Torao
 */
public class MessagePackCodec implements Codec {

	// ==============================================================================================
	// Singleton インスタンス
	// ==============================================================================================
	/**
	 * SimpleCodec は Singleton で使用します。
	 */
	public static final MessagePackCodec Instance = new MessagePackCodec();

	// ==============================================================================================
	// Singleton インスタンス
	// ==============================================================================================
	/**
	 * SimpleCodec は Singleton で使用します。
	 */
	public static MessagePackCodec getInstance(){
		return Instance;
	}

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 * コンストラクタは何も行いません。
	 */
	private MessagePackCodec() { }

	// ==============================================================================================
	// 直列化処理の取得
	// ==============================================================================================
	/**
	 * 直列化処理を参照します。
	 */
	public Marshal newMarshal(){
		return new MsgPackMarshal();
	}

	// ==============================================================================================
	// 非直列化処理の取得
	// ==============================================================================================
	/**
	 * 非直列化処理を参照します。
	 */
	public Unmarshal newUnmarshal(ByteBuffer buffer){
		return new MasPackUnmarshal(buffer);
	}

	/**
	 * 各転送可能型に対してビッグエンディアンでシリアライズを行います。
	 */
	private static class MsgPackMarshal implements Marshal {
		private final MessagePack msgpack = new MessagePack();
		private final BufferPacker packer = msgpack.createBufferPacker();
		public MsgPackMarshal(){ }
		public ByteBuffer toByteBuffer() {
			return ByteBuffer.wrap(packer.toByteArray());
		}
		public void writeInt8(byte i){
			try {
				packer.write(i);
			} catch(IOException ex){
				throw new IllegalStateException(ex);
			}
		}
		public void writeInt16(short i){
			try {
				packer.write(i);
			} catch(IOException ex){
				throw new IllegalStateException(ex);
			}
		}
		public void writeInt32(int i) {
			try {
				packer.write(i);
			} catch(IOException ex){
				throw new IllegalStateException(ex);
			}
		}
		public void writeInt64(long i) {
			try {
				packer.write(i);
			} catch(IOException ex){
				throw new IllegalStateException(ex);
			}
		}
		public void writeFloat32(float i) {
			try {
				packer.write(i);
			} catch(IOException ex){
				throw new IllegalStateException(ex);
			}
		}
		public void writeFloat64(double i){
			try {
				packer.write(i);
			} catch(IOException ex){
				throw new IllegalStateException(ex);
			}
		}
		public void writeBinary(byte[] b, int offset, int length){
			try {
				packer.write(b, offset, length);
			} catch(IOException ex){
				throw new IllegalStateException(ex);
			}
		}
	}

	/**
	 * バイナリから各転送可能型にデシリアライズを行います。
	 */
	private static class MasPackUnmarshal implements Unmarshal {
		private final BufferUnpacker unpacker;
		public MasPackUnmarshal(ByteBuffer buffer){
			this.unpacker = new MessagePack().createBufferUnpacker(buffer);
		}
		public byte readInt8() throws Unsatisfied {
			try {
				return unpacker.readByte();
			} catch(EOFException ex){
				throw new Unsatisfied();
			} catch(IOException ex){
				throw new IllegalStateException(ex);
			}
		}
		public short readInt16() throws Unsatisfied{
			try {
				return unpacker.readShort();
			} catch(EOFException ex){
				throw new Unsatisfied();
			} catch(IOException ex){
				throw new IllegalStateException(ex);
			}
		}
		public int readInt32() throws Unsatisfied {
			try {
				return unpacker.readInt();
			} catch(EOFException ex){
				throw new Unsatisfied();
			} catch(IOException ex){
				throw new IllegalStateException(ex);
			}
		}
		public long readInt64() throws Unsatisfied {
			try {
				return unpacker.readLong();
			} catch(EOFException ex){
				throw new Unsatisfied();
			} catch(IOException ex){
				throw new IllegalStateException(ex);
			}
		}
		public float readFloat32() throws Unsatisfied {
			try {
				return unpacker.readFloat();
			} catch(EOFException ex){
				throw new Unsatisfied();
			} catch(IOException ex){
				throw new IllegalStateException(ex);
			}
		}
		public double readFloat64() throws Unsatisfied {
			try {
				return unpacker.readDouble();
			} catch(EOFException ex){
				throw new Unsatisfied();
			} catch(IOException ex){
				throw new IllegalStateException(ex);
			}
		}
		public byte[] readBinary() throws Unsatisfied {
			try {
				return unpacker.readByteArray();
			} catch(EOFException ex){
				throw new Unsatisfied();
			} catch(IOException ex){
				throw new IllegalStateException(ex);
			}
		}
	}
}