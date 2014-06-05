/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque;

import org.asterisque.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSession;
import java.io.Closeable;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Wire
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * 通信経路の実装です。
 * 送受信 {@link org.asterisque.Message} キューを使用して下層の通信実装との帯域制限を行います。
 *
 * @author Takami Torao
 */
public abstract class Wire implements Closeable {
	private static final Logger logger = LoggerFactory.getLogger(Wire.class);

	/**
	 * この Wire が既にクローズされているかを表すフラグ。
	 */
	private final AtomicBoolean closed = new AtomicBoolean(false);

	/**
	 * メッセージ送信のためのキュー。
	 */
	private final MessageQueue rightToLeft;

	/**
	 * メッセージ受信のためのキュー。
	 */
	private final MessageQueue leftToRight;

	/**
	 * メッセージ受信時に呼び出すディスパッチャー。
	 */
	private final Consumer<Message> dispatcher;

	/**
	 * Wire がクローズされるときに呼び出すラムダ。
	 */
	private final Consumer<Wire> disposer;

	/**
	 * メッセージ受信時にディスパッチャーを実行するスレッドプール。
	 */
	private final Executor executor;

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 * 送受信用の 2 つの {@link io.asterisque.MessageQueue} を使用して下層の非同期 I/O フレームワークとメッ
	 * セージのやり取りを行うインターフェースです。
	 * 送信、受信ともに {@link org.asterisque.Message} の advisory limit を使用したバックプレッシャーの通知を
	 * 行います。
	 *
	 * @param sendSoftLimit 送信キューのソフトリミット
	 * @param sendHardLimit 送信キューのハードリミット
	 * @param sendBackPressure 送信キューに対するバックプレッシャー通知
	 * @param recvSoftLimit 受信キューのソフトリミット
	 * @param recvHardLimit 受信キューのハードリミット
	 * @param recvBackPressure 受信キューに対するバックプレッシャー通知
	 * @param dispatcher メッセージ受信処理
	 * @param disposer {@link #close()} メソッドによりこの Wire がクローズされたときに一度だけ呼び出される処理
	 * @param executor メッセージ受信処理を実行するスレッド
	 */
	protected Wire(
		int sendSoftLimit, int sendHardLimit, Consumer<Boolean> sendBackPressure,
		int recvSoftLimit, int recvHardLimit, Consumer<Boolean> recvBackPressure,
		Consumer<Message> dispatcher, Consumer<Wire> disposer, Executor executor){
		this.rightToLeft = new MessageQueue(sendSoftLimit, sendHardLimit, sendBackPressure, this::onSendQueueEmpty);
		this.leftToRight = new MessageQueue(recvSoftLimit, recvHardLimit, recvBackPressure, this::onMessageArrival);
		this.dispatcher = dispatcher;
		this.disposer = disposer;
		this.executor = executor;
	}

	// ==============================================================================================
	// 送信メッセージの参照
	// ==============================================================================================
	/**
	 * 送信キューから次の送信メッセージを取り出すためにサブクラスから呼び出します。送信キューが空の場合は
	 * Optional.empty() を返します。
	 */
	protected Optional<Message> nextSendMessage(){
		return rightToLeft.dequeue();
	}

	// ==============================================================================================
	// 送信メッセージ存在通知
	// ==============================================================================================
	/**
	 * 送信キューが空になった時、および空から1つのメッセージが到着した時に呼び出されます。
	 * サブクラスはこのメソッドをオーバーライドして下層の非同期 I/O の Socket Writable 通知を変更することが出来ます。
	 * @param empty 送信キューが空になったとき true
	 */
	protected void onSendQueueEmpty(boolean empty){ }

	// ==============================================================================================
	// 受信キュー通知
	// ==============================================================================================
	/**
	 * 受信キューにメッセージが投入された時に呼び出されます。スレッドプールのスレッドを使用して受信キューから
	 * メッセージを取り出し配信を行います。
	 */
	private void onMessageArrival(){
		executor.execute(() -> {
			Optional<Message> msg = leftToRight.dequeue();
			if(msg.isPresent()){
				dispatcher.accept(msg.get());
			}
		});
	}

	// ==============================================================================================
	// クローズ判定
	// ==============================================================================================
	/**
	 * この Wire がクローズされている時に true を返します。
	 */
	public boolean isClosed(){
		return closed.get();
	}

	// ==============================================================================================
	// クローズの実行
	// ==============================================================================================
	/**
	 * この Wire をクローズします。インスタンスに対して最初に呼び出されたタイミングでコンストラクタに指定した
	 * disposer へのコールバックが行われます。このメソッドを複数回呼び出しても二度目以降は無視されます。
	 *
	 * クローズされた Wire に対するメッセージ送信はエラーになります。
	 * クローズされた Wire が受信したメッセージは破棄されます。
	 */
	public void close(){
		if(closed.compareAndSet(false, true)){
			disposer.accept(this);
		}
	}

	// ==============================================================================================
	// サーバ判定
	// ==============================================================================================
	/**
	 * この Wire の表すエンドポイントがサーバ側の場合に true を返します。
	 *
	 * このフラグは通信相手との合意なしにユニークな ID を発行できるようにするために使用されます。例えば新しいパイプを
	 * 開くときの ID の最上位ビットを立てるかどうかに使用することで相手との合意なしにユニークなパイプ ID 発行を行って
	 * います。この取り決めから通信の双方でこの値が異なっている必要があります。
	 *
	 * @return サーバ側の場合 true
	 */
	public abstract boolean isServer();

	// ==============================================================================================
	// SSL セッションの参照
	// ==============================================================================================
	/**
	 * この Wire が通信相手とセキュアな通信経路を構築している場合にその SSLSession を参照します。
	 *
	 * @return 認証された通信相手の SSL セッション
	 * @throws java.lang.InterruptedException ハンドシェイク待機中に割り込みが発生した場合
	 */
	public abstract Optional<SSLSession> getSSLSession() throws InterruptedException;

	// ==============================================================================================
	// インスタンスの文字列化
	// ==============================================================================================
	/**
	 * この Wire インスタンスの接続状態を人が識別するための情報を返します。
	 */
	public abstract String toString();

	// ==============================================================================================
	// メッセージの送信
	// ==============================================================================================
	/**
	 * アプリケーションから指定されたメッセージを送信します。
	 * Wire が既にクローズされている場合は何も行いません。
	 *
	 * @param msg 送信するメッセージ
	 */
	public void send(byte priority, Message msg) {
		if(! isClosed()) {
			try {
				rightToLeft.enqueue(priority, msg);
			} catch(MessageQueue.HardLimitReached ex) {
				logger.error("hard limit reached, closing wire: " + toString());
				close();
			} catch(MessageQueue.MaxSequenceReached ex) {
				logger.error("queue expired, closing wire: " + toString());
				close();
			}
		} else {
			logger.debug(String.format("departure message disposed on closed wire: %s", msg));
		}
	}

	// ==============================================================================================
	// メッセージの受信
	// ==============================================================================================
	/**
	 * 下層のネットワーク実装がメッセージを受信したときに呼び出されます。制御メッセージの場合は
	 * Wire が既にクローズされている場合はログに出力するだけで何も行いません。
	 *
	 * @param msg 受信したメッセージ
	 */
	protected void receive(Message msg) {
		if(! isClosed()){
			try {
				leftToRight.enqueue(Priority.Normal, msg);
			} catch(MessageQueue.HardLimitReached ex) {
				logger.error("hard limit reached, closing wire: " + toString());
				close();
			} catch(MessageQueue.MaxSequenceReached ex) {
				logger.error("queue expired, closing wire: " + toString());
				close();
			}
			if(logger.isTraceEnabled()){
				logger.trace(String.format("message queued: %s", msg));
			}
		} else {
			logger.debug(String.format("arrival message disposed on closed wire: %s", msg));
		}
	}

	// ==============================================================================================
	// ワイヤーの参照
	// ==============================================================================================
	/**
	 * メッセージの伝達が双方向に連結された Wire ペアを作成します。
	 * @return 接続された長さ 2 の Wire 配列
	 */
	public static Wire[] newPipedWire(int softLimit, int hardLimit){
		PipedWirePair pair = new PipedWirePair(softLimit, hardLimit);
		return new Wire[]{ pair.wire1, pair.wire2 };
	}

	public static final class Priority {
		public static final byte Normal = 0;
		public static final byte Min = Byte.MIN_VALUE;
		public static final byte Max = Byte.MAX_VALUE;
		private Priority(){ }
		public static byte lower(byte priority){
			if(priority == Min){
				return Min;
			}
			return (byte)(priority - 1);
		}
		public static byte upper(byte priority){
			if(priority == Max){
				return Max;
			}
			return (byte)(priority + 1);
		}
	}

}

class PipedWirePair {
	private static final Executor executor = Executors.newSingleThreadExecutor();
	public final Wire wire1;
	public final Wire wire2;
	private final AtomicBoolean closing = new AtomicBoolean(false);
	public PipedWirePair(int softLimit, int hardLimit){
		class PipedWire extends Wire {
			private final boolean server;
			public PipedWire(boolean isServer, Consumer<Message> dispatcher){
				super(softLimit, hardLimit, b->{}, softLimit, hardLimit, b->{}, dispatcher, w->PipedWirePair.this.close(), executor);
				this.server = isServer;
			}
			@Override
			public boolean isServer() { return server; }
			@Override
			public Optional<SSLSession> getSSLSession() { return Optional.empty(); }
			@Override
			public String toString() { return server? "": ""; }
		}
		this.wire1 = new PipedWire(true, this::leftToRight);
		this.wire2 = new PipedWire(false, this::rightToLeft);
	}
	private void leftToRight(Message msg){ wire2.receive(msg); }
	private void rightToLeft(Message msg){ wire1.receive(msg); }
	private void close(){
		if(closing.compareAndSet(false, true)){
			wire1.close();
			wire2.close();
		}
	}
}
