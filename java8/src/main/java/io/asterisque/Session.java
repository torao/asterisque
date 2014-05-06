/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Session
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * ピアとの通信状態を表すクラスです。
 *
 * @author Takami Torao
 */
public class Session extends Attributes{
	private static final Logger logger = LoggerFactory.getLogger(Session.class);

	public final LocalNode node;
	public final String name;
	public final Wire wire;

	/**
	 *
	 * @param node
	 * @param name このセッションの名前
	 * @param defaultService このセッション上でピアに公開する初期状態のサービス
	 * @param wire このセッションのワイヤー
	 */
	Session(LocalNode node, String name, Service defaultService, Wire wire){
		this.node = node;
		this.name = name;
		this.service = defaultService;
		this.wire = wire;
		this.pipeIdMask = wire.isServer()? Pipe.UniqueMask: 0;

		// `Wire` とこのセッションを結合しメッセージポンプを開始
		wire.onClose.add(w -> close());
		/*
		wire.onReceive ++ dispatch        	// `Wire` にメッセージが到着した時のディスパッチャーを設定
		wire.onClosed ++ { _ => close() } 	// `Wire` がクローズされたときにセッションもクローズ
		wire.start()                      	// メッセージポンプを開始
		*/

	}


	/**
	 * このセッションで提供しているサービス。
	 */
	private volatile Service service;

	/**
	 * このセッション上でピアに公開するサービスを変更します。
	 * @param s 新しく公開するサービス
	 * @return 以前のサービス
	 */
	public Service setService(Service s){
		Service old = service;
		service = s;
		return old;
	}

	/**
	 * このセッションで生成するパイプの ID に付加するビットマスク。
	 */
	private final short pipeIdMask;

	/**
	 * 新規のパイプ ID を発行するためのシーケンス番号。
	 */
	private final AtomicInteger pipeSequence = new AtomicInteger(0);

	/**
	 * このセッション上でオープンされているパイプ。
	 */
	private final AtomicReference<Map<Short,Pipe>> pipes = new AtomicReference<>(new HashMap<>());

	/**
	 * このセッションがクローズ処理中かを表すフラグ。
	 */
	private final AtomicBoolean closing = new AtomicBoolean(false);

	/**
	 * このセッションがクローズされているかを表すフラグ。
	 */
	private final AtomicBoolean closed = new AtomicBoolean(false);

	/**
	 * このセッションがクローズされたときに呼び出されるイベントハンドラです。
	 */
	public final EventHandlers<Session> onClosed = new EventHandlers<>();

	// ==============================================================================================
	// パイプのオープン
	// ==============================================================================================
	/**
	 * このセッション上のピアに対して指定された function との非同期呼び出しのためのパイプを作成します。
	 *
	 * @param function function の識別子
	 * @param params function の実行パラメータ
	 * @param onTransferComplete 呼び出し先とのパイプが生成されたときに実行される処理
	 * @return パイプに対する Future
	 */
	public CompletableFuture<Object> open(short function, Object[] params,
																				Function<Pipe,CompletableFuture<Object>> onTransferComplete) {
		Pipe pipe = create(function);
		pipe.open(params);
		CompletableFuture<Object> future = Pipe.using(pipe, () -> onTransferComplete.apply(pipe) );
		pipe.startMessagePump();
		return future;
	}
	public CompletableFuture<Object> open(short function, Object[] params) {
		return open(function, params, pipe -> pipe.future);
	}

	/**
	 * 指定されたメッセージを受信したときに呼び出されその種類によって処理を分岐します。
	 */
	private void dispatch(Message msg){
		if(logger.isTraceEnabled()){
			logger.trace("dispatch: " + msg);
		}
		Pipe pipe;
		if(msg instanceof Open) {
			pipe = create((Open) msg);
		} else {
			pipe = pipes.get().get(msg.pipeId);
		}
		if(pipe != null) {
			if(msg instanceof Open) {
				// サービスを起動しメッセージポンプの開始
				dispatch(pipe, msg);
				pipe.startMessagePump();
			} else {
				// Open は受信したがサービスの処理が完了していない状態でメッセージを受信した場合にパイプのキューに保存できるよう
				// 一度パイプを経由して dispatch(Pipe,Message) を実行する
				pipe.dispatch(msg);
			}
		} else {
			logger.debug("unknown pipe-id: " + msg);
			if(msg instanceof Close){
				logger.debug("both of sessions unknown pipe #" + msg.pipeId);
			} else {
				post(Close.unexpectedError(msg.pipeId, "unknown pipe-id: " + msg.pipeId));
			}
		}
	}

	/**
	 * 指定されたメッセージを受信したときに呼び出されその種類によって処理を分岐します。
	 */
	private void dispatch(Pipe pipe, Message msg) {
		try {
			Pipe.using(pipe, () -> service.dispatch(pipe, msg));
		} catch(Throwable ex){
			logger.error("unexpected error: " + msg + ", closing pipe", ex);
			post(Close.unexpectedError(msg.pipeId, "internal error"));
			if(ex instanceof ThreadDeath){
				throw (ThreadDeath)ex;
			}
		}
	}

	// ==============================================================================================
	// パイプの構築
	// ==============================================================================================
	/**
	 * ピアから受信した Open メッセージに対応するパイプを構築します。オープンに成功した場合は新しく構築されたパイプ
	 * を返します。
	 */
	private Optional<Pipe> create(Open open) {
		while(true){
			Map<Short,Pipe> map = pipes.get();
			// 既に使用されているパイプ ID が指定された場合はエラーとしてすぐ終了
			if(map.containsKey(open.pipeId)){
				logger.debug("duplicate pipe-id specified: " + open.pipeId + "; " + map.get(open.pipeId));
				post(Close.unexpectedError(open.pipeId, "duplicate pipe-id specified: " + open.pipeId));
				return Optional.empty();
			}
			// 新しいパイプを構築して登録
			Pipe pipe = new Pipe(open.pipeId, open.functionId, this);
			Map<Short,Pipe> newMap = new HashMap<>(map);
			newMap.put(pipe.id, pipe);
			if(pipes.compareAndSet(map, newMap)){
				return Optional.of(pipe);
			}
		}
	}

	// ==============================================================================================
	// パイプの構築
	// ==============================================================================================
	/**
	 * ピアに対して Open メッセージを送信するためのパイプを生成します。
	 */
	private Pipe create(short function){
		while(true) {
			Map<Short, Pipe> map = pipes.get();
			short id = (short) ((pipeSequence.getAndIncrement() & 0x7FFF) | pipeIdMask);
			if(!map.containsKey(id)) {
				Pipe pipe = new Pipe(id, function, this);
				Map<Short,Pipe> newMap = new HashMap<>(map);
				newMap.put(pipe.id, pipe);
				if(pipes.compareAndSet(map, newMap)){
					return pipe;
				}
			}
		}
	}

	// ==============================================================================================
	// パイプの破棄
	// ==============================================================================================
	/**
	 * このセッションが保持しているパイプのエントリから該当するパイプを切り離します。
	 */
	void destroy(short pipeId) {
		while(true){
			Map<Short, Pipe> map = pipes.get();
			Map<Short, Pipe> newMap = new HashMap<>(map);
			if(newMap.remove(pipeId) == null || pipes.compareAndSet(map, newMap)){
				return;
			}
		}
	}

	// ==============================================================================================
	// メッセージの送信
	// ==============================================================================================
	/**
	 * ピアに対して指定されたメッセージを送信します。
	 */
	void post(Message msg) throws IOException {
		if(closed.get()){
			throw new IOException("session " + name + " closed");
		} else {
			wire.send(msg);
			if(logger.isTraceEnabled()){
				logger.trace("post: " + msg);
			}
		}
	}

	// ==============================================================================================
	// セッションのクローズ
	// ==============================================================================================
	/**
	 * このセッションをクローズします。
	 * 実行中のすべてのパイプはクローズされ、以後のメッセージ配信は行われなくなります。
	 */
	public void close() {
		if(closing.compareAndSet(false, true)) {
			logger.trace("close(): " + name);

			Map<Short,Pipe> map;
			while(true){
				map = pipes.get();
				if(pipes.compareAndSet(map, Collections.emptyMap())){
					break;
				}
			}

			// 残っているすべてのパイプに Close メッセージを送信
			map.values().forEach( pipe -> {
				pipe.close(new Close(pipe.id, new Abort(Abort.SessionClosing, "session " + name + " closing")));
			});

			post(new Control(Control.Close, new byte[0]));

			// 以降のメッセージ送信をすべて例外に変更して送信を終了
			// ※Pipe#close() で Session#post() が呼び出されるためすべてのパイプに Close を投げた後に行う
			closed.set(true);

			// Wire のクローズ
			wire.close();

			// セッションのクローズを通知
			onClosed.accept(this);
		}
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
				return open(export.value(), (args==null? new Object[0]: args));
			}
		}

	}

}
