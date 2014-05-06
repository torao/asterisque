/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Pipe
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * function に対する呼び出し状態を表すクラスです。function の呼び出し開始から終了までのスコープを持ち、その呼び
 * 出し結果は `pipe.future` を使用して非同期で参照することができます。またパイプは非同期メッセージングにおける
 * メッセージの送出先/流入元を表しており同期ストリーミングもパイプを経由して行います。
 *
 * パイプの ID は `Session` を開始した側 (クライアント側) が最上位ビット 0、相手からの要求を受け取った側 (サー
 * ビス側) が 1 を持ちます。このルールは通信の双方で相手側との合意手続きなしに重複しないユニークなパイプ ID を
 * 発行することを目的としています。このため一つのセッションで同時に行う事が出来る呼び出しは最大で 32,768、パイプを
 * 共有しているピアの双方で 65,536 個までとなります。
 *
 * @author Takami Torao
 */
public class Pipe extends Attributes {

	public final short id;
	public final short function;
	public final Session session;

	/**
	 *
	 * @param id パイプ ID
	 * @param function このパイプの呼び出し先 function 番号
	 * @param session このパイプのセッション
	 */
	Pipe(short id, short function, Session session) {
		this.id = id;
		this.function = function;
		this.session = session;
		logger.trace(signature + ": pipe created");
		// ブロックを受信したらメッセージソースに通知
		onBlock.add(src);
	}

	/**
	 * このパイプに対する受信処理を設定する前に受信したメッセージのキューです。
	 */
	private BlockingQueue<Message> premature = new LinkedBlockingQueue<>();

	private AtomicBoolean messagePump = new AtomicBoolean(false);

	void dispatch(Message msg) {
		if(messagePump.get()) {
			session.dispatch(this, msg);
		} else {
			synchronized(premature) {
				if(messagePump.get()) {
					session.dispatch(this, msg);
				} else {
					premature.put(msg);
				}
			}
		}
	}

	void startMessagePump() {
		synchronized(premature) {
			while(!premature.isEmpty()) {
				session.dispatch(this, premature.take());
			}
			messagePump.set(true);
		}
	}

	/**
	 * このパイプがクローズされているかどうかを表すアトミックなフラグ。
	 */
	private AtomicBoolean closed = new AtomicBoolean(false);

	/**
	 * このパイプがクローズされているときに true を返します。
	 */
	public boolean isClosed() {
		return closed.get();
	}

	/**
	 * このパイプにブロックが到着した時に呼び出すイベントハンドラ。
	 * アプリケーションはこのハンドラではなく [[src]] を使用する。
	 */
	EventHandlers<Block> onBlock = new EventHandlers<>();

	/**
	 * このパイプがクローズされたときに呼び出すイベントハンドラ。
	 * アプリケーションはこのハンドラではなく [[future]] を使用する。
	 */
	private EventHandlers<Boolean> onClosing = new EventHandlers<>();

	/**
	 * このパイプがクローズされて確定した結果を通知するための `Promise`。
	 * このパイプによる双方の処理で確定した結果 (成功/失敗にかかわらず) を参照するための `Future` です。
	 * パイプの結果が確定した時点でパイプはクローズされています。
	 */
	public final CompletableFuture<Object> future = new CompletableFuture<>();

	/**
	 * どのパイプで何が起きたかをトレースするためのログ出力用のシンボル文字列。
	 */
	final String signature = "#" + (session.wire.isServer ? "S" : "C") + (id & 0xFFFF);

	// ==============================================================================================
	// Open メッセージの送信
	// ==============================================================================================

	/**
	 * このパイプが示す function 番号に対して指定された引数で Open メッセージを送信します。
	 *
	 * @param params function 呼び出し時の引数
	 */
	void open(Object[] params) {
		logger.trace(signature + ": sending open");
		Open open = new Open(id, function, params);
		session.post(open);
	}

	// ==============================================================================================
	// ブロックの送信
	// ==============================================================================================

	/**
	 * 指定されたバイナリデータを非同期メッセージングのメッセージとして Block を送信します。
	 */
	private void block(byte[] buffer, int offset, int length) {
		block(new Block(id, buffer, offset, length));
	}

	// ==============================================================================================
	// ブロックの受信
	// ==============================================================================================

	/**
	 * 指定された Block メッセージを非同期メッセージングのメッセージとして送信します。
	 */
	private void block(Block block) {
		if(logger.isTraceEnabled()) {
			logger.trace(signature + ": sending block: " + block);
		}
		session.post(block);
	}

	// ==============================================================================================
	// パイプのクローズ
	// ==============================================================================================

	/**
	 * 指定された結果で Close メッセージを送信しパイプを閉じます。
	 * このメソッドは正常に結果が確定したときの動作です。
	 *
	 * @param result Close に付加する結果
	 */
	void close(Object result) {
		if(closed.compareAndSet(false, true)) {
			onClosing.accept(true);
			session.post(new Close(id, result));
			future.complete(result);
			session.destroy(id);
			logger.trace(signature + ": pipe is closed with success: " + result);
		} else {
			logger.debug(signature + ": pipe already closed: " + result);
		}
	}

	// ==============================================================================================
	// パイプのクローズ
	// ==============================================================================================

	/**
	 * 指定した例外で Close を送信しパイプを閉じます。
	 * このメソッドはエラーが発生したときの動作です。
	 *
	 * @param ex Close に付加する例外
	 */
	void close(Throwable ex, String msg) {
		if(closed.compareAndSet(false, true)) {
			onClosing.accept(true);
			session.post(new Close(id, new Abort(Abort.Unexpected, msg)));
			future.completeExceptionally(ex);
			session.destroy(id);
			logger.trace(signature + ": pipe is closed with failure: " + ex);
		} else {
			logger.debug(signature + ": pipe already closed: " + ex);
		}
	}

	// ==============================================================================================
	// パイプのクローズ
	// ==============================================================================================

	/**
	 * 相手側から受信した Close メッセージによってこのパイプを閉じます。
	 */
	void close(Close close) {
		if(closed.compareAndSet(false, true)) {
			onClosing.accept(false);
			if(close.abort.isPresent()) {
				future.completeExceptionally(close.abort.get());
			} else {
				future.complete(close.result.get());
			}
			session.destroy(id);
			logger.trace(signature + ": pipe is closed by peer: " + close);
		} else {
			logger.debug(signature + ": pipe already closed: " + close);
		}
	}

	/**
	 * 非同期メッセージングの Block メッセージ受信を行うメッセージソース。
	 */
	public final Source<Block> src = new MessageSource();


	/**
	 * このパイプによる非同期メッセージングの受信処理を設定する非同期コレクションです。
	 * この非同期コレクションは処理の呼び出しスレッド内でしか受信処理を設定することが出来ません。
	 */
	public Source<Block> src() {
		return src;
	}

	/**
	 * このパイプに対する非同期メッセージングの送信を行うメッセージシンクです。
	 * クローズされていないパイプに対してであればどのようなスレッドからでもメッセージの送信を行うことが出来ます。
	 */
	public final MessageSink sink = new MessageSink();

	/**
	 * このパイプに対するブロック出力を `OutputStream` として行います。ストリームに対する出力データがバッファ
	 * サイズに達するか `flush()` が実行されると [[Block]] として送信されます。
	 * このストリームへの出力操作は I/O ブロックを受けません。
	 */
	public OutputStream out() {
		if(out.get() == null) {
			PipeOutputStream o = new PipeOutputStream(this);
			if(out.compareAndSet(null, o)) {
				onClosing.add(me -> {
					if(me){
						o.close();
					} else {
						o.emergencyClose();
					}
				});
			}
		}
		return out.get();
	}

	private final AtomicReference<PipeOutputStream> out = new AtomicReference<>(null);

	/**
	 * このパイプが受信したブロックを `InputStream` として参照します。
	 *
	 * @@[[com.kazzla.asterisk.Export]](stream=true) 宣言されているメソッドのパイプのみ使用することが
	 * 出来ます。
	 */
	private final AtomicReference<PipeInputStream> in = new AtomicReference<>(null);

	/**
	 * このパイプがメッセージとして受信したバイナリデータを `InputStream` として参照します。
	 * 非同期で到着するメッセージを同期ストリームで参照するために、入力ストリームを使用する前に [[useInputStream()]]
	 * を呼び出して内部的なキューを準備しておく必要があります。[[useInputStream()]] を行わず入力ストリームを参照
	 * した場合は例外となります。
	 */
	public InputStream in() throws IOException {
		if(in.get() == null) {
			throw new IOException(signature + ": useInputStream() is not declared on pipe");
		} else {
			return in.get();
		}
	}

	/**
	 * 非同期で到着するバイナリーデータを [[in]] を使用した同期ストリームとして参照するためのキューを準備します。
	 * このメソッドを呼び出すと受信したブロックのバッファリグが行われますので、入力ストリームを使用する場合のみ
	 * 呼び出してください。
	 */
	public void useInputStream() {
		assertInCall("useInputStream() must be call in caller thread, Ex. session.open(10){_.useInputStream()}, 10.accept{withPipe{pipe=>pipe.useInputStream();...}}");
		PipeInputStream in = new PipeInputStream(signature);
		src.foreach(in);
		this.in.set(in);
		onClosing.add(me -> {
			if(me) {
				in.close();
			} else if(!in.isClosed()) {
				in.accept(Block.eof(id));
			}
		});
		logger.trace(signature + ": prepare internal buffer for messaging that is used for InputStream");
	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// MessageSink
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

	/**
	 */
	public class MessageSink {
		private MessageSink() {
		}

		// ============================================================================================
		// ブロックの送信
		// ============================================================================================

		/**
		 * メッセージの終了を示す EOF メッセージを非同期メッセージとして送信します。
		 */
		public MessageSink sendEOF() {
			block(Block.eof(Pipe.this.id));
			return this;
		}

		// ============================================================================================
		// ブロックの送信
		// ============================================================================================

		/**
		 * 指定されたバイナリデータを非同期メッセージとして送信します。
		 * このメソッドは渡された buffer をそのまま送信バッファとして使用します。従ってメソッド呼び出しが終わった後に
		 * バッファの内容を変更した場合、実際に送信されるデータは保証されません。
		 */
		public MessageSink sendDirect(byte[] buffer) {
			sendDirect(buffer, 0, buffer.length);
			return this;
		}

		// ============================================================================================
		// ブロックの送信
		// ============================================================================================

		/**
		 * 指定されたバイナリデータを非同期メッセージとして送信します。
		 * このメソッドは渡された buffer をそのまま送信バッファとして使用します。従ってメソッド呼び出しが終わった後に
		 * バッファの内容を変更した場合、実際に送信されるデータは保証されません。
		 */
		public MessageSink sendDirect(byte[] buffer, int offset, int length) {
			Pipe.this.block(buffer, offset, length);
			return this;
		}

		// ============================================================================================
		// ブロックの送信
		// ============================================================================================

		/**
		 * 指定されたバイナリデータを非同期メッセージとして送信します。
		 */
		public MessageSink send(ByteBuffer buffer) {
			byte[] payload = new byte[buffer.remaining()];
			buffer.get(payload);
			sendDirect(payload);
			return this;
		}

		// ============================================================================================
		// ブロックの送信
		// ============================================================================================

		/**
		 * 指定されたバイナリデータを非同期メッセージとして送信します。
		 */
		public MessageSink send(byte[] buffer) {
			send(buffer, 0, buffer.length);
			return this;
		}

		// ============================================================================================
		// ブロックの送信
		// ============================================================================================

		/**
		 * 指定されたバイナリデータを非同期メッセージとして送信します。
		 */
		public MessageSink send(byte[] buffer, int offset, int length) {
			byte[] payload = new byte[length];
			System.arraycopy(buffer, offset, payload, 0, length);
			sendDirect(payload, 0, length);
			return this;
		}

	}

	private static final Logger logger = LoggerFactory.getLogger(Pipe.class);

	/**
	 * スレッドに結びつけられたパイプを参照するためのスレッドローカル。
	 */
	private static final ThreadLocal<Pipe> pipes = new ThreadLocal<>();

	/**
	 * 現在のスレッドにむつび付けられているパイプを参照します。
	 */
	private static Optional<Pipe> currentPipe() {
		if(pipes.get() == null) {
			return Optional.empty();
		} else {
			return Optional.of(pipes.get());
		}
	}

	/**
	 * 現在のスレッドにパイプが関連づけられていない場合に指定されたメッセージ付きの例外を発生します。
	 *
	 * @param msg 例外メッセージ
	 */
	static void assertInCall(String msg) {
		if(pipes.get() == null) {
			throw new IllegalStateException(msg);
		}
	}

	public static <T> T orElse(Supplier<T> def, Function<Pipe, T> exec) {
		Optional<Pipe> p = currentPipe();
		if(p.isPresent()) {
			return exec.apply(p.get());
		} else {
			return def.get();
		}
	}

	static <T> T using(Pipe pipe, Supplier<T> exec) {
		Pipe old = pipes.get();
		pipes.set(pipe);
		try {
			return exec.get();
		} finally {
			pipes.set(old);
		}
	}

	/**
	 * [[com.kazzla.asterisk.Wire.isServer]] が true の通信端点側で新しいパイプ ID を発行するときに立てる
	 * ビット。
	 */
	static final short UniqueMask = (short) (1 << 15);

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// MessageSource
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

	/**
	 * パイプに対してブロック送受信 (ストリーム) 操作をおこなうためのクラス。
	 * メッセージ配信スレッド内でハンドラを設定する必要があります。
	 */
	private class MessageSource extends Source<Block> implements Consumer<Block> {

		public MessageSource() {
		}

		/**
		 * Block メッセージ受信時に呼び出される処理。このインスタンスに設定されているコンビネータ (関数) に転送する。
		 *
		 * @param block 受信したメッセージ
		 */
		public void accept(Block block) {
			synchronized(this) {
				sequence(block);
				if(block.eof) {
					finish();
				}
			}
		}

		/**
		 * コンビネータの設定を行えるかの評価。パイプが参照できないスレッドの場合は例外となる。
		 */
		@Override
		public void onAddOperation() {
			assertInCall("operation for message passing can only define in caller thread");
			super.onAddOperation();
		}

	}
}
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// PipeInputStream
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * パイプ上で受信した Block メッセージのバイナリデータをストリームとして参照するためのクラス。
 */
class PipeInputStream extends InputStream implements Consumer<Block> {
	private static final Logger logger = LoggerFactory.getLogger(PipeInputStream.class);

	private final String signature;
	public PipeInputStream(String signature){
		this.signature = signature;
	}

	/**
	 * パイプから受信したブロックを Block として保持するためのキュー。
	 */
	private BlockingQueue<Block> receiveQueue = new LinkedBlockingQueue<Block>();

	/**
	 * 読み出し中の ByteBuffer。
	 */
	private Optional<ByteBuffer> processing = Optional.empty();

	/**
	 * ストリーム上で EOF を検知したかのフラグ。
	 */
	private boolean eof = false;

	/**
	 * インスタンスの利用側によってクローズされたかのフラグ。
	 */
	private boolean closed = false;

	public boolean isClosed(){
		return closed;
	}

	public void accept(Block block) {
		if(!closed) {
			if(logger.isTraceEnabled()) {
				logger.trace(signature + ": apply(" + block + ") enqueueing specified block message as stream data");
			}
			receiveQueue.put(block)
		}
	}

	public int read() throws IOException{
		Optional<ByteBuffer> buffer = processingBuffer();
		if(buffer.isPresent()) {
			ensureOpen();
			return buffer.get().get() & 0xFF;
		} else {
			return -1;
		}
	}

	@Override
	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}

	@Override
	public int read(byte[] b, int offset, int length) throws IOException{
		Optional<ByteBuffer> buffer = processingBuffer();
		if(buffer.isPresent()){
			ensureOpen();
			int len = Math.min(length, buffer.get().remaining());
			buffer.get().get(b, offset, len);
			return len;
		} else {
			return -1;
		}
	}

	@Override
	public void close(){
		closed = true;
		logger.trace(signature + ": close()");
	}

	private Optional<ByteBuffer> processingBuffer() throws IOException {
		if(eof){
			return Optional.empty();
		} else if(processing.isPresent() && processing.get().hasRemaining()){
			return processing;
		//} else if(receiveQueue.isEmpty){
		//	None
		} else try {
			Block block = receiveQueue.take();
			if(block.eof){
				if(logger.isTraceEnabled()){
					logger.trace(signature + ": eof detected, closing stream");
				}
				eof = true;   // EOF 検出
				return Optional.empty();
			} else {
				if(logger.isTraceEnabled()){
					logger.trace(signature + ": dequeue block message for stream: " + block);
				}
				processing = Optional.of(block.toByteBuffer());
				return processing;
			}
		} catch(InterruptedException ex){
			throw new IOException("operation interrupted", ex);
		}
	}
	private void ensureOpen() throws IOException{
		if(closed){
			throw new IOException("stream closed");
		}
	}
}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// PipeOutputStream
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * パイプに対してバイナリデータを Block メッセージとして送信するための出力ストリームです。出力されたバイナリは内部
 * でバッファリングされ、フラグメント化されたブロックとして送信されます。
 * バッファ容量に達した場合やフラッシュされた場合はバッファの内容を Block メッセージにカプセル化して非同期で
 * セッションへ送信します。
 */
class PipeOutputStream extends OutputStream {
	private static final Logger logger = LoggerFactory.getLogger(PipeOutputStream.class);
	private final Pipe pipe;
	private ByteBuffer buffer;
	private boolean osClosed = false;
	public PipeOutputStream(Pipe pipe, int bufferSize){
		this.pipe = pipe;
		this.buffer = ByteBuffer.allocate(bufferSize);
	}

	public PipeOutputStream(Pipe pipe){
		this(pipe, 4 * 1024);
	}

	/**
	 * 出力バッファサイズを変更します。バッファリングしているデータはすべて [[flush()]] されます。
	 * @param size 出力バッファサイズ
	 */
	public void setBufferSize(int size){
		flush();
		assert(buffer.position() == 0);
		buffer = ByteBuffer.allocate(size);
	}

	public void write(int b) throws IOException {
		ensureWrite(1);
		buffer.put((byte)b);
	}
	@Override
	public void write(byte[] b) throws IOException {
		ensureWrite(b.length);
		buffer.put(b);
	}
	@Override
	public void write(byte[] b, int offset, int length) throws IOException {
		ensureWrite(length);
		buffer.put(b, offset, length);
	}

	private void ensureWrite(int len) throws IOException {
		if(osClosed){
			throw new IOException(pipe.signature + ": unable to write to closed pipe or stream: " + pipe.id);
		}
		// TODO バッファサイズより大きなデータが書き込めない?
		if(buffer.position() + len > buffer.capacity()){
			flush();
		}
	}
	@Override
	public void flush(){
		if(logger.isTraceEnabled()){
			logger.trace(pipe.signature + ": flush()");
		}
		if(buffer.position() > 0){
			buffer.flip();
			while(buffer.hasRemaining()){
				int len = Math.min(buffer.remaining(), Block.MaxPayloadSize);
				byte[] b = new byte[len];
				buffer.get(b, 0, b.length);
				pipe.sink.send(b);
			}
			buffer.clear();
		}
	}
	@Override
	public void close(){
		if(! osClosed){
			flush();
			pipe.sink.sendEOF();
			osClosed = true;
			logger.trace(pipe.signature + ": close()");
		}
	}
	public void emergencyClose() {
		osClosed = true;
		logger.trace(pipe.signature + ": output stream closed by peer");
	}
}
