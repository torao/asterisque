/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

import org.asterisque.util.CircuitBreaker;
import org.asterisque.util.LooseBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Session
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * ピアとの通信状態を表すクラスです。
 * {@code Wire} の再接続を行います。
 *
 * @author Takami Torao
 */
public class Session {
	private static final Logger logger = LoggerFactory.getLogger(Session.class);

	public final UUID id;
	public final LocalNode node;
	public final boolean isServer;
	public final Attributes attributes = new Attributes();

	public Optional<SocketAddress> local(){ return wire.map(Wire::local); }
	public Optional<SocketAddress> remote(){ return wire.map(Wire::remote); }

	private final AtomicBoolean closing = new AtomicBoolean(false);
	private final AtomicBoolean closed = new AtomicBoolean(false);

	private final Supplier<Optional<CompletableFuture<Wire>>> wireFactory;
	private volatile Optional<Wire> wire = Optional.empty();
	private volatile Service service;

	private final DepartureGate departure;
	private final PipeSpace pipes;
	private final Wire.Plug plug;

	/** 出力キューの高負荷を検知するブレーカー */
	final CircuitBreaker writeBreaker;
	final CircuitBreaker readBreaker;

	/**
	 * Block の同期出力 {@link org.asterisque.PipeOutputStream#write(byte[], int, int)} を一時停止させる
	 * ためのバリア。
	 */
	final LooseBarrier writeBarrier;

	/**
	 * このセッションがクローズされたときに呼び出されるイベントハンドラです。
	 */
	public final EventHandlers<Session> onClosed = new EventHandlers<>();

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 *
	 */
	Session(UUID id, LocalNode node, boolean isServer, Service defaultService, Options options,
					Supplier<Optional<CompletableFuture<Wire>>> wireFactory){
		this.id = id;
		this.node = node;
		this.isServer = isServer;
		this.service = defaultService;
		this.wireFactory = wireFactory;

		this.writeBarrier = new LooseBarrier();

		int readSoftLimit = options.get(Options.KEY_READ_SOFT_LIMIT).get();
		int readHardLimit = options.get(Options.KEY_READ_HARD_LIMIT).get();
		int writeSoftLimit = options.get(Options.KEY_WRITE_SOFT_LIMIT).get();
		int writeHardLimit = options.get(Options.KEY_WRITE_HARD_LIMIT).get();

		this.writeBreaker = new CircuitBreaker(writeSoftLimit, writeHardLimit) {
			@Override
			protected void overload(boolean overload) {
				// Wire 出力が過負荷になった場合は同期 Block 送信を一時停止
				writeBarrier.lock(overload);
			}
			@Override
			protected void broken() {
				logger.error("write hard-limit reached, closing session");
				reconnect();
			}
		};

		this.readBreaker = new CircuitBreaker(readSoftLimit, readHardLimit) {
			@Override
			protected void overload(boolean overload) {
				// 同期 Block 読み込みが過負荷になった場合は Wire からの読み込み一時停止
				wire.get().setReadable(!overload);
			}
			@Override
			protected void broken() {
				logger.error("read hard-limit reached, closing session");
				reconnect();
			}

		};

		this.departure = new DepartureGate(this, writeSoftLimit);

		this.pipes = new PipeSpace(this);

		this.plug = new Wire.Plug(){
			@Override
			public Message produce() { return departure.ship(); }
			@Override
			public void consume(Message msg) { deliver(msg); }
			@Override
			public void onClose(Wire wire) { reconnect(); }
		};

		logger.debug(id + ": session created");

		// 非同期で Wire を構築してメッセージの開始
		connect();
	}

	// ==============================================================================================
	// サービスの設定
	// ==============================================================================================
	/**
	 * このセッション上でピアに公開するサービスを設定します。
	 * @param s 新しく公開するサービス
	 * @return 以前のサービス
	 */
	public Service setService(Service s){
		Service old = service;
		service = s;
		return old;
	}

	/**
	 * @return このセッションがクローズプロセスに入っている場合 true
	 */
	public boolean closing(){ return closing.get(); }

	/**
	 * @return このセッションがクローズを完了している場合 true
	 */
	public boolean closed(){ return closed.get(); }

	// ==============================================================================================
	// セッションのクローズ
	// ==============================================================================================
	/**
	 * このセッションを好意的にクローズします。このメソッドは {@code close(true)} と等価です。
	 */
	public void close() {
		close(true);
	}

	// ==============================================================================================
	// セッションのクローズ
	// ==============================================================================================
	/**
	 * このセッションをクローズします。
	 * 実行中のすべてのパイプはクローズされ、以後のメッセージ配信は行われなくなります。
	 */
	public void close(boolean graceful) {
		if(closing.compareAndSet(false, true)) {
			logger.trace("close(" + graceful + ")");

			// 残っているすべてのパイプに Close メッセージを送信
			pipes.close(graceful);

			if(graceful){
				post(Wire.Priority.Normal, new Control(Control.Close, new byte[0]));
			}

			// 以降のメッセージ送信をすべて例外に変更して送信を終了
			// ※Pipe#close() で Session#post() が呼び出されるためすべてのパイプに Close を投げた後に行う
			closed.set(true);

			// Wire のクローズ
			wire.get().close();

			// セッションのクローズを通知
			onClosed.accept(this);
		}
	}

	// ==============================================================================================
	// 接続の実行
	// ==============================================================================================
	/**
	 * このセッションを非同期で接続状態にします。
	 */
	private void connect() {
		logger.debug(id + ": connecting...");
		Optional<CompletableFuture<Wire>> opt = wireFactory.get();
		if(opt.isPresent()) {
			opt.get().thenAccept(this::onConnect).exceptionally(ex -> {
				logger.error(id + ": fail to connect: " + remote(), ex);
				return null;
			});
		} else {
			logger.debug(id + ": reconnection not supported: " + remote());
		}
	}

	private void onConnect(Wire wire){
		this.wire = Optional.of(wire);
		this.departure.wire(Optional.of(wire));
		wire.setPlug(Optional.of(this.plug));
	}

	private void reconnect() {
		// TODO 再接続のストラテジーを実装する (exponential backoff など)
		disconnect();
		connect();
	}

	private void disconnect(){
		wire.ifPresent(w -> {
			wire = Optional.empty();
			w.setPlug(Optional.empty());
			w.close();
		});
		// TODO DepartureGate のクリア
		// TODO PIpeSpace 内の全ての Pipe を失敗で終了
	}

	// ==============================================================================================
	// パイプのオープン
	// ==============================================================================================
	/**
	 * このセッション上のピアに対して指定された function との非同期呼び出しのためのパイプを作成します。
	 *
	 * @param priority 新しく生成するパイプの同一セッション内でのプライオリティ
	 * @param function function の識別子
	 * @param params function の実行パラメータ
	 * @param onTransferComplete 呼び出し先とのパイプが生成されたときに実行される処理
	 * @return パイプに対する Future
	 */
	public CompletableFuture<Object> open(byte priority, short function, Object[] params,
																				Function<Pipe,CompletableFuture<Object>> onTransferComplete) {
		Pipe pipe = pipes.create(priority, function);
		pipe.open(params);
		return Pipe.using(pipe, () -> onTransferComplete.apply(pipe) );
	}

	public CompletableFuture<Object> open(byte priority, short function, Object[] params) {
		return open(priority, function, params, pipe -> pipe.future);
	}

	// ==============================================================================================
	// メッセージの配信
	// ==============================================================================================
	/**
	 * 指定されたメッセージを受信したときに呼び出されその種類によって処理を分岐します。
	 */
	private void deliver(Message msg){
		if(logger.isTraceEnabled()){
			logger.trace("deliver: " + msg);
		}

		// Control メッセージはこのセッション内で処理する
		if(msg instanceof Control){
			Control ctrl = (Control)msg;
			switch(ctrl.code){
				case Control.Close:
					close(false);
					break;
				default:
					logger.error("unsupported control code: 0x" + Integer.toHexString(ctrl.code & 0xFF));
					throw new ProtocolViolationException("");
			}
			return;
		}

		// メッセージの配信先パイプを参照
		Optional<Pipe> _pipe;
		if(msg instanceof Open){
			_pipe = pipes.create((Open) msg);
		} else {
			_pipe = pipes.get(msg.pipeId);
		}

		// パイプが定義されていない場合
		if(! _pipe.isPresent()) {
			if(msg instanceof Close) {
				logger.debug("both of sessions unknown pipe #" + msg.pipeId);
			} else if(msg instanceof Open){
				post(Wire.Priority.Normal, Close.unexpectedError(msg.pipeId, "duplicate pipe-id specified: " + msg.pipeId));
			} else if(msg instanceof Block){
				logger.debug("unknown pipe-id: " + msg);
				post(Wire.Priority.Normal, Close.unexpectedError(msg.pipeId, "unknown pipe-id specified: " + msg.pipeId));
			}
			return;
		}

		Pipe pipe = _pipe.get();
		try {
			if(msg instanceof Open) {
				// サービスを起動しメッセージポンプの開始
				Open open = (Open)msg;
				service.dispatch(pipe, open);
			} else if(msg instanceof Block) {
				// Open は受信したがサービスの処理が完了していない状態でメッセージを受信した場合にパイプのキューに保存できるよう
				// 一度パイプを経由して deliver(Pipe,Message) を実行する
				pipe.dispatchBlock((Block) msg);
			} else if(msg instanceof Close){
				pipe.close((Close)msg);
			} else {
				throw new IllegalStateException("unexpected message: " + msg);
			}
		} catch(Throwable ex){
			logger.error("unexpected error: " + msg + ", closing pipe " + pipe, ex);
			post(pipe.priority, Close.unexpectedError(msg.pipeId, "internal error"));
			if(ex instanceof ThreadDeath){
				throw (ThreadDeath)ex;
			}
		}
	}

	// ==============================================================================================
	// メッセージの送信
	// ==============================================================================================
	/**
	 * ピアに対して指定されたメッセージを送信します。
	 */
	void post(byte priority, Message msg) {
		if(closing()){
			logger.error("session " + id + " closed");
		} else {
			try {
				departure.forward(priority, msg);
				if(logger.isTraceEnabled()) {
					logger.trace("post: " + msg);
				}
			} catch(DepartureGate.MaxSequenceReached ex) {
				logger.warn("max sequence reached on wire, reconnecting", ex);
				reconnect();
			} catch(DepartureGate.HardLimitReached ex){
				logger.error("write queue reached hard limit by pending messages, reconnecting", ex);
				reconnect();
			}
		}
	}

	void destroy(short pipeId){
		pipes.destroy(pipeId);
	}

	// ==============================================================================================
	// リモートインターフェースの参照
	// ==============================================================================================
	/**
	 * このセッションの相手側となるインターフェースを参照します。
	 */
	public <T> T bind(Class<T> clazz){
		return clazz.cast(java.lang.reflect.Proxy.newProxyInstance(
			Thread.currentThread().getContextClassLoader(),
			new Class[]{ clazz }, new Skeleton(clazz)
		));
	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// Skeleton
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * リモート呼び出し先の function を @Export 定義されたメソッドとして扱うための動的プロキシ用ハンドラ。
	 */
	private class Skeleton implements InvocationHandler {

		public Skeleton(Class<?> clazz){
			// 指定されたインターフェースのすべてのメソッドに @Export アノテーションが付けられていることを確認
			Optional<String> methods = Stream.of(clazz.getDeclaredMethods())
				.filter(m -> m.getAnnotation(Export.class) == null)
				.map(Debug::getSimpleName).reduce((a, b) -> a + "," + b);
			if(methods.isPresent()){
				throw new IllegalArgumentException(
					"@" + Export.class.getSimpleName() + " annotation is not specified on: " + methods.get());
			}
		}

		// ============================================================================================
		// リモートメソッドの呼び出し
		// ============================================================================================
		/**
		 * リモートメソッドを呼び出します。
		 * @param proxy プロキシオブジェクト
		 * @param method 呼び出し対象のメソッド
		 * @param args メソッドの引数
		 * @return 返し値
		 */
		public Object invoke(Object proxy, Method method, Object[] args) throws InvocationTargetException, IllegalAccessException {
			Export export = method.getAnnotation(Export.class);
			if(export == null){
				// toString() や hashCode() など Object 型のメソッド呼び出し?
				logger.debug("normal method: " + Debug.getSimpleName(method));
				return method.invoke(this, args);
			} else {
				// there is no way to receive block in interface binding
				byte priority = export.priority();
				short function = export.value();
				return open(priority, function, (args==null? new Object[0]: args));
			}
		}

	}

	@Override
	public String toString(){
		return id.toString();
	}

}
