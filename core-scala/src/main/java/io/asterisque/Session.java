/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

import io.asterisque.Debug;
import io.asterisque.Priority;
import io.asterisque.msg.*;
import org.asterisque.util.CircuitBreaker;
import org.asterisque.util.Latch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLPeerUnverifiedException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.security.Principal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Session
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * ピアとの通信状態を表すクラスです。
 *
 * ピア間での幾つかの取り決めを実装する目的で、便宜的に通信開始側をクライアント (worker)、接続受付側をサーバ
 * (master) と定めます。
 *
 * セッションはピアとのメッセージ送受信のための {@code Wire} を持ちます (Wire を持たない状態は物理的に接続して
 * いない状態を表します)。セッションは Wire が切断されると新しい Wire の接続を試みます。
 *
 * セッション ID はサーバ側
 * セッションが構築されると {@link SyncConfig 状態同期} のための
 * {@link Control} メッセージがクライアントから送信されます。サーバはこのメッセージを受け
 * ると新しいセッション ID を発行して状態同期の {@link Control} メッセージで応答し、双方の
 * セッションが開始します。
 *
 * @author Takami Torao
 */
public class Session {
	private static final Logger logger = LoggerFactory.getLogger(Session.class);

	/** セッション ID。ピアと状態同期が済んでいない場合は {@link org.asterisque.Asterisque#Zero} の値を取る。 */
	public final UUID id(){ return _id; }
	/** このセッションのノード */
	public final Node node;
	/** こちら側のセッションがサーバの場合 true */
	public final boolean isServer;
	/**
	 * セッションのスコープで保持することの出来る属性値。{@link org.asterisque.cluster.Repository} に保管
	 * 可能な値のみ使用可能。
	 */
	public final Attributes attributes = new Attributes();

	/** セッション ID */
	private UUID _id = Asterisque.Zero;
	/** このセッションが使用している Wire から受信した状態同期メッセージ */
	private Optional<SyncConfig> header = Optional.empty();

	/** このセッションのローカル側の物理アドレスを参照します。ピアと接続していない場合は Optional.empty() を返します。 */
	public Optional<SocketAddress> local(){ return wire.map(Wire::local); }
	/** このセッションのリモート側の物理アドレスを参照します。ピアと接続していない場合は Optional.empty() を返します。 */
	public Optional<SocketAddress> remote(){ return wire.map(Wire::remote); }

	/** このセッションがクローズ中またはクローズ完了済み1を表すフラグ */
	private final AtomicBoolean closing = new AtomicBoolean(false);
	/** このセッションがクローズ完了済みを表すフラグ */
	private final AtomicBoolean closed = new AtomicBoolean(false);

	private final Supplier<Optional<CompletableFuture<Wire>>> wireFactory;
	private volatile Optional<Wire> wire = Optional.empty();
	private volatile Service service;

	private final Options options;
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
	final Latch writeBarrier;

	/**
	 * このセッションがクローズされたときに呼び出されるイベントハンドラです。
	 */
	public final EventHandlers<Session> onClosed = new EventHandlers<>();

	/**
	 * このセッション上での出力保留メッセージ数が soft limit に達しバックプレッシャーがかかったときに呼び出されます。
	 */
	public final EventHandlers<Boolean> onBackPressure = new EventHandlers<>();

	private final BiConsumer<Session,UUID> onSync;

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 *
	 */
	Session(Node node, boolean isServer, Service defaultService, Options options,
					Supplier<Optional<CompletableFuture<Wire>>> wireFactory, BiConsumer<Session,UUID> onSync){
		this._id = Asterisque.Zero;
		this.node = node;
		this.isServer = isServer;
		this.service = defaultService;
		this.wireFactory = wireFactory;
		this.onSync = onSync;

		this.writeBarrier = new Latch();

		this.options = options;
		int readSoftLimit = options.get(Options.KEY_READ_SOFT_LIMIT).get();
		int readHardLimit = options.get(Options.KEY_READ_HARD_LIMIT).get();
		int writeSoftLimit = options.get(Options.KEY_WRITE_SOFT_LIMIT).get();
		int writeHardLimit = options.get(Options.KEY_WRITE_HARD_LIMIT).get();

		this.writeBreaker = new CircuitBreaker(writeSoftLimit, writeHardLimit) {
			@Override
			protected void overload(boolean overload) {
				// Wire 出力が過負荷になった場合は同期 Block 送信を一時停止
				writeBarrier.lock(overload);
				// ハンドラへの通知
				onBackPressure.accept(overload);
			}
			@Override
			protected void broken() {
				logger.error(id() + ": write hard-limit reached, closing session");
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
				logger.error(id() + ": read hard-limit reached, closing session");
				reconnect();
			}

		};

		this.departure = new DepartureGate(this, writeSoftLimit);

		this.pipes = new PipeSpace(this);

		this.plug = new Wire.Plug(){
			@Override
			public Message produce() { return departure.ship(); }
			@Override
			public void consume(Message msg) {
				try {
					deliver(msg);
				} catch(ProtocolViolationException ex){
					logger.error(logId() + ": protocol violation", ex);
					close(true);
				}
			}
			@Override
			public void onClose(Wire wire) { reconnect(); }
			@Override
			public String id(){ return logId(); }
		};

		logger.debug(logId() + ": session created, waiting sync-config");

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
			logger.debug(logId() + ": closing session with " + (graceful? "graceful": "forceful"));

			// 残っているすべてのパイプに Close メッセージを送信
			pipes.close(graceful);

			if(graceful){
				post(Priority.Normal, new Control(Control.Close));
			}

			// 以降のメッセージ送信をすべて例外に変更して送信を終了
			// ※Pipe#close() で Session#post() が呼び出されるためすべてのパイプに Close を投げた後に行う
			closed.set(true);

			// Wire のクローズ
			disconnect();

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
		logger.debug(logId() + ": connecting...");
		Optional<CompletableFuture<Wire>> opt = wireFactory.get();
		if(opt.isPresent()) {
			opt.get().thenAccept(this::onConnect).exceptionally(ex -> {
				logger.error(id() + ": fail to connect: " + remote(), ex);
				return null;
			});
		} else {
			logger.debug(logId() + ": reconnection not supported: " + remote());
		}
	}

	private void onConnect(Wire wire){
		this.wire = Optional.of(wire);
		this.departure.wire(Optional.of(wire));
		wire.setPlug(Optional.of(this.plug));

		wire.setReadable(true);

		// クライアントであればヘッダの送信
		if(! isServer){
			// ※サーバ側からセッションIDが割り当てられていない場合は Zero が送信される
			int ping = options.get(Options.KEY_PING_REQUEST).get();
			int timeout = options.get(Options.KEY_SESSION_TIMEOUT_REQUEST).get();
			SyncConfig header = new SyncConfig(node.id, Asterisque.Zero, System.currentTimeMillis(), ping, timeout);
			post(Priority.Normal, header.toControl());
		}
	}

	private void reconnect() {
		// TODO 再接続のストラテジーを実装する (exponential backoff など)
		disconnect();
		connect();
	}

	private void disconnect(){
		header = Optional.empty();
		wire.ifPresent(w -> {
			wire = Optional.empty();
			w.setPlug(Optional.empty());
			w.close();
		});
		// TODO DepartureGate のクリア
		// TODO PipeSpace 内の全ての Pipe を失敗で終了
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
			logger.trace(logId() + ": deliver: " + msg);
		}
		ensureSessionStarted(msg);

		// Control メッセージはこのセッション内で処理する
		if(msg instanceof Control){
			Control ctrl = (Control)msg;
			switch(ctrl.code){
				case Control.SyncConfig:
					sync(SyncConfig.parse(ctrl));
					break;
				case Control.Close:
					close(false);
					break;
				default:
					logger.error(id() + ": unsupported control code: 0x" + Integer.toHexString(ctrl.code & 0xFF));
					throw new ProtocolViolationException("unsupported control");
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
				logger.debug(logId() + ": both of sessions unknown pipe #" + msg.pipeId);
			} else if(msg instanceof Open){
				post(Priority.Normal, Close.unexpectedError(msg.pipeId, "duplicate pipe-id specified: " + msg.pipeId));
			} else if(msg instanceof Block){
				logger.debug(logId() + ": unknown pipe-id: " + msg);
				post(Priority.Normal, Close.unexpectedError(msg.pipeId, "unknown pipe-id specified: " + msg.pipeId));
			}
			return;
		}

		Pipe pipe = _pipe.get();
		try {
			if(msg instanceof Open) {
				// サービスを起動しメッセージポンプの開始
				Open open = (Open)msg;
				service.dispatch(pipe, open, logId());
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
			logger.error(id() + ": unexpected error: " + msg + ", closing pipe " + pipe, ex);
			post(pipe.priority, Close.unexpectedError(msg.pipeId, "internal error"));
			if(ex instanceof ThreadDeath){
				throw (ThreadDeath)ex;
			}
		}
	}


	/**
	 * @return ping 間隔 (秒)。接続していない場合は 0。
	 */
	public int getPingInterval(){
		return header.map( h -> {
			if(isServer){
				int maxPing = options.get(Options.KEY_MAX_PING).get();
				int minPing = options.get(Options.KEY_MIN_PING).get();
				return Math.min(maxPing, Math.max(minPing, h.ping));
			} else {
				return h.ping;
			}
		}).orElse(0);
	}

	/**
	 * @return セッションタイムアウト (秒)。接続していない場合は 0。
	 */
	public int getTimeout(){
		return header.map( h -> {
			if(isServer){
				int maxSession = options.get(Options.KEY_MAX_SESSION_TIMEOUT).get();
				int minSession = options.get(Options.KEY_MIN_SESSION_TIMEOUT).get();
				return Math.min(maxSession, Math.max(minSession, h.sessionTimeout));
			} else {
				return h.sessionTimeout;
			}
		}).orElse(0);
	}

	// ==============================================================================================
	// セッション同期の実行
	// ==============================================================================================
	/**
	 * 新たな Wire 接続から受信したストリームヘッダでこのセッションの内容を同期化します。
	 */
	private void sync(SyncConfig header) throws ProtocolViolationException {
		if(this.header.isPresent()){
			throw new ProtocolViolationException("multiple sync message");
		}
		this.header = Optional.of(header);
		if(isServer){
			// サーバ側の場合は応答を返す
			if(header.sessionId.equals(Asterisque.Zero)){
				// 新規セッションの開始
				this._id = node.repository.nextUUID();
				logger.trace(logId() + ": new session-id is issued: " + this._id);
				SyncConfig ack = new SyncConfig(
					Asterisque.Protocol.Version_0_1, node.id, _id, System.currentTimeMillis(), getPingInterval(), getTimeout());
				post(Priority.Normal, ack.toControl());
			} else {
				Optional<Principal> principal = Optional.empty();
				try {
					principal = Optional.of(wire.get().getSSLSession().get().getPeerPrincipal());
				} catch(SSLPeerUnverifiedException ex){
					// TODO クライアント認証が無効な場合? 動作確認
					logger.debug(logId() + ": client authentication ignored: " + ex);
				}
				// TODO リポジトリからセッション?サービス?を復元する
				Optional<byte[]> service = node.repository.loadAndDelete(principal, header.sessionId);
				if(service.isPresent()) {
					SyncConfig ack = new SyncConfig(
						Asterisque.Protocol.Version_0_1, node.id, _id, System.currentTimeMillis(), getPingInterval(), getTimeout());
					post(Priority.Normal, ack.toControl());
				} else {
					// TODO retry after
					post(Priority.Normal, new Control(Control.Close));
				}
			}
		} else {
			// クライアントの場合はサーバが指定したセッション ID を保持
			if(header.sessionId.equals(Asterisque.Zero)){
				throw new ProtocolViolationException("session-id is not specified from server: " + header.sessionId);
			}
			if(this._id.equals(Asterisque.Zero)) {
				this._id = header.sessionId;
				logger.trace(logId() + ": new session-id is specified: " + this._id);
			} else if(! this._id.equals(header.sessionId)){
				throw new ProtocolViolationException("unexpected session-id specified from server: " + header.sessionId + " != " + this._id);
			}
		}
		logger.info(logId() + ": sync-configuration success, beginning session");
		onSync.accept(this, header.sessionId);
	}

	// ==============================================================================================
	// メッセージの送信
	// ==============================================================================================
	/**
	 * ピアに対して指定されたメッセージを送信します。
	 */
	void post(byte priority, Message msg) {
		if(closed() || (closing() && ! (msg instanceof Control))){
			logger.error("session " + id() + " closed");
		} else {
			try {
				departure.forward(priority, msg);
				if(logger.isTraceEnabled()) {
					logger.trace(logId() + ": post: " + msg);
				}
			} catch(DepartureGate.MaxSequenceReached ex) {
				logger.warn(id() + ": max sequence reached on wire, reconnecting", ex);
				reconnect();
			} catch(DepartureGate.HardLimitReached ex){
				logger.error(id() + ": write queue reached hard limit by pending messages, reconnecting", ex);
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
			// 指定されたインターフェースの全てのメソッドが CompletableFuture の返値を持つことを確認
			// TODO Scala の Future も許可したい
			Stream.of(clazz.getDeclaredMethods())
				.filter(m -> ! m.getReturnType().equals(CompletableFuture.class))
				.map(Debug::getSimpleName).reduce((a, b) -> a + "," + b).ifPresent( name -> {
					throw new IllegalArgumentException(
						"methods without return-type CompletableFuture<?> exists: " + name);
				});
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
				logger.debug(logId() + ": normal method: " + Debug.getSimpleName(method));
				return method.invoke(this, args);
			} else {
				// there is no way to receive block in interface binding
				logger.debug(logId() + ": calling remote method: " + Debug.getSimpleName(method));
				byte priority = export.priority();
				short function = export.value();
				return open(priority, function, (args==null? new Object[0]: args));
			}
		}

	}

	@Override
	public String toString(){
		return id().toString();
	}

	String logId() {
		return Asterisque.logPrefix(isServer, id());
	}

	/** このセッションが開始していることを保証する処理。*/
	private void ensureSessionStarted(Message msg){
		if(! header.isPresent() && (! (msg instanceof Control) || ((Control)msg).code != Control.SyncConfig)) {
			logger.error(id() + ": unexpected message received; session is not initialized yet: " + msg);
			throw new ProtocolViolationException("unexpected message received");
		}
	}

}
