/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.codec;

import io.asterisque.Message;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.Optional;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// JavaCodec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * Message codec implementation using Java object serialization.
 * A Message will encode to 2 byte binary length and serialized binary.
 *
 * @author Takami Torao
 */
public class JavaCodec implements Codec {
	public final ClassLoader loader;
	public JavaCodec(ClassLoader loader){ this.loader = loader; }
	public JavaCodec(){ this(Thread.currentThread().getContextClassLoader()); }

	// ==============================================================================================
	// メッセージのシリアライズ
	// ==============================================================================================
	/**
	 * Java シリアライゼーションを使用してメッセージをエンコードします。
	 * メッセージは 2 バイトの長さとシリアライズされたバイナリに変換されます。
	 * @param msg エンコードするメッセージ
	 * @return エンコードされたメッセージ
	 */
	public ByteBuffer encode(Message msg) throws CodecException{
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			baos.write(new byte[Short.BYTES]);		// 後で長さを格納するためパディング
			ObjectOutputStream out = new ObjectOutputStream(baos);
			out.writeObject(msg);
			out.flush();
			out.close();
		} catch(Exception ex){
			throw new CodecException("unexpected exception", ex);
		}
		ByteBuffer buffer = ByteBuffer.wrap(baos.toByteArray());
		if(buffer.remaining() > MaxMessageSize){
			String message = String.format("serialized size too long: %,d bytes > %,d bytes", buffer.remaining(), MaxMessageSize);
			throw new CodecException(message);
		}
		buffer.position(0);
		buffer.putShort((short)(buffer.remaining() - Short.BYTES));
		buffer.position(0);
		return buffer;
	}

	// ==============================================================================================
	// メッセージのデシリアライズ
	// ==============================================================================================
	/**
	 * Java シリアライゼーションを使用してメッセージをデコードします。
	 * @param buffer デコードするメッセージ
	 * @return デコードしたメッセージ
	 */
	public Optional<Message> decode(ByteBuffer buffer) throws CodecException{
		try {
			if(buffer.remaining() < Short.BYTES) {
				return Optional.empty();
			}
			int len = buffer.getShort() & 0xFFFF;
			if(buffer.remaining() < len){
				buffer.position(buffer.position() - Short.BYTES);
				return Optional.empty();
			}
			byte[] buf = new byte[len];
			buffer.get(buf);
			ByteArrayInputStream bais = new ByteArrayInputStream(buf);
			ObjectInputStream in = new ObjectInputStream(bais){
				protected Class<?> resolveClass(ObjectStreamClass desc) throws ClassNotFoundException, IOException {
					String name = desc.getName();
					try {
						return Class.forName(name, false, loader);
					} catch(ClassNotFoundException ex){
						return super.resolveClass(desc);
					}
				}
			};
			return Optional.of((Message)in.readObject());
		} catch(InvalidClassException ex) {
			throw new CodecException(ex.classname, ex);
		} catch(Exception ex){
			throw new CodecException("invalid serialization stream: " + ex, ex);
		}
	}

	// ==============================================================================================
	// 直列化処理の取得
	// ==============================================================================================
	/**
	 * 直列化処理を参照します。
	 */
	public Marshal newMarshal(){
		return new SimpleCodec.SimpleMarshal();
	}

	// ==============================================================================================
	// 非直列化処理の取得
	// ==============================================================================================
	/**
	 * 非直列化処理を参照します。
	 */
	public Unmarshal newUnmarshal(ByteBuffer buffer){
		return new SimpleCodec.SimpleUnmarshal(buffer);
	}

}