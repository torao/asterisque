/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSession;
import java.io.Closeable;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Wire
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * 通信経路の実装です。
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
	 * メッセージ受信時にディスパッチャーを実行するスレッドプール。
	 */
	private final Executor executor;

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 * 送受信用の 2 つの {@link io.asterisque.MessageQueue} を使用して下層の非同期 I/O フレームワークとメッ
	 * セージのやり取りを行うインターフェースです。
	 * 送信、受信ともに {@link io.asterisque.Message} の advisory limit を使用したバックプレッシャーの通知を
	 * 行います。
	 *
	 * @param sendAdvisoryLimit 送信キューのアドバイザリーリミット
	 * @param sendBlockingLimit 送信キューのブロックリミット
	 * @param sendBackPressure 送信キューに対するバックプレッシャー通知
	 * @param sendEmpty 送信キューの空、非空通知
	 * @param recvAdvisoryLimit 受信キューのアドバイザリーリミット
	 * @param recvBlockingLimit 受信キューのブロックリミット
	 * @param recvBackPressure 受信キューに対するバックプレッシャー通知
	 * @param dispatcher メッセージ受信処理
	 * @param executor メッセージ受信処理を実行するスレッド
	 */
	protected Wire(
		int sendAdvisoryLimit, int sendBlockingLimit, Consumer<Boolean> sendBackPressure, Consumer<Boolean> sendEmpty,
		int recvAdvisoryLimit, int recvBlockingLimit, Consumer<Boolean> recvBackPressure, Consumer<Message> dispatcher,
		Executor executor){
		this.rightToLeft = new MessageQueue(sendAdvisoryLimit, sendBlockingLimit, sendBackPressure, sendEmpty);
		this.leftToRight = new MessageQueue(recvAdvisoryLimit, recvBlockingLimit, recvBackPressure, this::onMessageArrival);
		this.dispatcher = dispatcher;
		this.executor = executor;
	}

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
	// クローズイベントハンドラ
	// ==============================================================================================
	/**
	 * {@link #close()} メソッドによりこの Wire がクローズされたときに一度だけ呼び出されるイベントハンドラです。
	 */
	public final EventHandlers<Wire> onClose = new EventHandlers<>();

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
	 * この Wire をクローズします。インスタンスに対して最初に呼び出されたタイミングで {@link #onClose} イベント
	 * ハンドラへのコールバックが行われます。このメソッドを複数回呼び出しても二度目以降は無視されます。
	 *
	 * クローズされた Wire に対するメッセージ送信はエラーになります。
	 * クローズされた Wire が受信したメッセージは破棄されます。
	 */
	public void close(){
		if(closed.compareAndSet(false, true)){
			onClose.accept(this);
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
	// 通信先アドレスの参照
	// ==============================================================================================
	/**
	 * この Wire の通信相手のアドレスを参照します。
	 * {@link #isServer()} が false を返すサブクラスは返値のアドレスを使用して再接続が可能でなければいけません。
	 * アドレスの {@link io.asterisque.Wire.PeerAddress#toString()} は通信相手を人が識別するための文字列を
	 * 返します。
	 *
	 * @return 通信相手を人が識別するための文字列
	 */
	public abstract PeerAddress getPeerAddress();

	// ==============================================================================================
	// SSL セッションの参照
	// ==============================================================================================
	/**
	 * この Wire が通信相手とセキュアな通信経路を構築している場合にその SSLSession を参照します。
	 *
	 * @return 認証された通信相手の SSL セッション
	 */
	public abstract Optional<SSLSession> getSSLSession();

	// ==============================================================================================
	// メッセージの送信
	// ==============================================================================================
	/**
	 * アプリケーションから指定されたメッセージを送信します。
	 * Wire が既にクローズされている場合は何も行いません。
	 *
	 * @param msg 送信するメッセージ
	 */
	public void send(Message msg) throws InterruptedException {
		if(! isClosed()) {
			rightToLeft.enqueue(msg);
		} else {
			logger.debug(String.format("departure message disposed on closed wire: %s", msg));
		}
	}

	// ==============================================================================================
	// メッセージの受信
	// ==============================================================================================
	/**
	 * 下層のネットワーク実装がメッセージを受信したときに呼び出します。
	 * Wire が既にクローズされている場合は何も行いません。
	 *
	 * @param msg 受信したメッセージ
	 */
	protected void receive(Message msg) throws InterruptedException{
		if(! isClosed()){
			if(msg instanceof Control) {
				Control ctrl = (Control)msg;
				switch(ctrl.code){
					case Control.Close:
						close();
						logger.debug("wire closed normally by peer");
						break;
					default:
						close();
						logger.error(String.format("unexpected control message detected: %s", ctrl));
						break;
				}
			} else {
				leftToRight.enqueue(msg);
				if(logger.isTraceEnabled()){
					logger.trace(String.format("message queued: %s", msg));
				}
			}
		} else {
			logger.debug(String.format("arrival message disposed on closed wire: %s", msg));
		}
	}

	public static interface PeerAddress {
		public String toString();
	}

}
