/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// DepartureGate
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * 非同期の {@link org.asterisque.Wire} へ渡す出力メッセージを最終的に直列化するクラスです。
 *
 * このキューが送信可能なメッセージを持つかどうかで Wire に対して {@code setWritable(false or true)} の操作
 * をおこないます。
 *
 * @author Takami Torao
 */
class DepartureGate {
	private static final Logger logger = LoggerFactory.getLogger(DepartureGate.class);

	public final Session session;

	/**
	 * 出力用のメッセージを保持するキュー。
	 */
	private final BlockingQueue<Entry> queue;

	/**
	 * 出力されるメッセージのシーケンス番号。
	 * {@link java.util.PriorityQueue} が同一優先度の順位を維持しないため全てのメッセージ付加し整列させる目的で
	 * 使用する。有限だが 1sec に 1000 メッセージ enqueue するとしても全てを消費するまで 557 MegaYear が必要で
	 * あるため、上限に達したら例外を送出する (このキューを使用する Wire がクローズされる) ことで対処する。
	 */
	private final AtomicLong sequence = new AtomicLong(Long.MIN_VALUE);

	/**
	 * このキューが接続している Wire。キュー状態によって Readable, Writable 状態を変更するために保持している。
	 */
	private volatile Optional<Wire> wire = Optional.empty();

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 * @param initSize 初期状態のキューバッファサイズ
	 */
	public DepartureGate(Session session, int initSize) {
		this.session = session;
		this.queue = new PriorityBlockingQueue<>(initSize);
	}

	// ==============================================================================================
	// Wire の設定
	// ==============================================================================================
	/**
	 * このキューと連動する Wire を設定します。
	 */
	public void wire(Optional<Wire> wire){
		assert(! session.writeBreaker.isBroken());
		this.wire = wire;
		// wire を初期状態に設定
		this.wire.ifPresent(w -> w.setWritable(!queue.isEmpty()));
	}

	// ==============================================================================================
	// 送信キューの状態更新
	// ==============================================================================================
	/**
	 * 送信キューの状態が変化した時に呼び出されます。
	 */
	private void onWriteQueueChanged(boolean enqueue, int size) {
		if(enqueue){
			if(size == 1){
				wire.ifPresent(w -> w.setWritable(true));
			}
			session.writeBreaker.increment();
		} else {
			if(size == 0){
				wire.ifPresent(w -> w.setWritable(false));
			}
			session.writeBreaker.decrement();
		}
	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// MessageQueue
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * 非同期 I/O 実装の Wire と Session 間ででメッセージの中継を行うための片方向キューです。
	 *
	 * キューは保持するメッセージ数が soft limit に達すると入力側に softLimit(true) 通知を行います。ただし soft
	 * limit 到達に対して具体的な動作制限が行われるわけではなく、単純にに通知が発生するのみです。入力側は
	 * softLimit(true) 通知を受け取った場合、次に suspend(false) 通知を受け取るまで新しいメッセージを投入すべき
	 * ではありません。enqueue() によって保持しているメッセージ数が hard limit を超えると例外が発生します。これは
	 * キューを使用している Wire がエラーによってクローズすることを意味します。
	 *
	 * 全てのメッセージがキューから読み出されると出力側は empty(true) 通知を受け取ります。この後に新しくメッセージ
	 * が到着すると empty(false) が通知されます。Selector を使用した非同期 I/O の場合はこの通知を使用して
	 * SelectionKey.OP_WRITE を制御する事が出来ます。
	 *
	 * キューに投入するメッセージの優先順位付けは行われません。
	 * {@link org.asterisque.Control} はキューの先頭へ優先的に追加されます。
	 *
	 * @author Takami Torao
	 */

	/**
	 * キューへのメッセージ追加や取り出しが行われたときに呼び出されるメソッド。第一引数は enqueue の場合に true、
	 * dequeue の場合に false。第二引数はキュー変更後のサイズ。
	 */

	// ============================================================================================
	// メッセージの追加
	// ============================================================================================
	/**
	 * このメッセージキューに指定されたメッセージを追加します。メッセージは優先順位 {@code priority} によって
	 * キュー内で優先順位付けが行われます。同一の優先順位を持つメッセージの順序が逆転することはありません。
	 *
	 * このキューが空だった場合は onEmpty(false) コールバックが発生します。
	 * キューが保持するメッセージ数が soft limit に達した場合は onSoft(true) が発生します。
	 * この呼び出しで hard limit + 1 に達する場合は空きが出来るまで呼び出し側のスレッドをブロックします。
	 *
	 * @param msg キューに投入するメッセージ
	 * @throws DepartureGate.HardLimitReached キューが hard limit に達した場合
	 * @throws DepartureGate.MaxSequenceReached シーケンス値が最大に達した場合
	 */
	public void forward(byte priority, Message msg) throws HardLimitReached, MaxSequenceReached {
		// 次のシーケンスを取得
		long seq = sequence.getAndIncrement();
		if(seq == Long.MAX_VALUE){
			sequence.decrementAndGet();
			throw new MaxSequenceReached();
		}
		// メッセージをキューに投入
		Entry entry = new Entry(msg, priority, seq);
		int size;
		synchronized(queue){
			queue.add(entry);
			size = queue.size();
		}
		onWriteQueueChanged(true, size);
	}

	// ============================================================================================
	// メッセージの取り出し
	// ============================================================================================
	/**
	 * このキューの先頭からブロッキングなしでメッセージを取り出します。このキューが有効なメッセージを持たない場合は
	 * Optional.empty() を返します。
	 *
	 * この呼び出しによりキューが空になった場合は onEmpty(true) 通知が発生します。
	 * またキューが保持するメッセージ数が soft limit - 1 に達した場合は onSoftLimit(false) が発生します。
	 *
	 * @return 取り出したメッセージ、またはキューが空の場合は Optional.empty()
	 */
	public Message ship() {
		assert(! queue.isEmpty());
		Entry entry;
		int size;
		synchronized(queue){
			entry = queue.remove();
			size = queue.size();
		}
		onWriteQueueChanged(false, size);
		return entry.msg;
	}

	/**
	 * キューに投入するエントリ。
	 */
	private static class Entry implements Comparable<Entry> {
		public final Message msg;
		public final byte priority;
		public final long sequence;
		public Entry(Message msg, byte priority, long sequence){
			this.msg = msg;
			this.priority = priority;
			this.sequence = sequence;
		}
		@Override
		public int compareTo(Entry other) {
			// priority の大きい方を前に
			if(this.priority > other.priority) return -1;
			if(this.priority < other.priority) return 1;
			// sequence の小さい方を前に
			if(this.sequence < other.sequence) return -1;
			if(this.sequence > other.sequence) return 1;
			return 0;		// this == other 以外で同一順位の存在はありえないので例外にしても良いかもしれない
		}
	}

	public static class HardLimitReached extends Exception { }
	public static class MaxSequenceReached extends Exception { }
}
