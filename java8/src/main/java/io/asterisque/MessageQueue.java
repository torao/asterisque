/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque;

import org.asterisque.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// MessageQueue
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * 非同期 I/O と asterisque のフレームワーク間でメッセージの中継を行うためのキューです。
 *
 * キューは保持するメッセージ数が soft limit に達すると入力側に softLimit(true) 通知を行います。ただし soft
 * limit 到達に対して具体的な動作制限が行われるわけではなく、単純にに通知が発生するのみです。入力側は
 * softLimit(true) 通知を受け取った場合、次に suspend(false) 通知を受け取るまで新しいメッセージを投入すべき
 * ではありません。enqueue() によって保持しているメッセージ数が hard limit を超えると例外が発生します。これは
 * キューを使用している Wire がエラーによってクローズすることを意味します。
 *
 * 全てのメッセージがキューから読み出されると出力側は empty(true) 通知を受け取ります。この後に新しくメッセージが
 * 到着すると empty(false) が通知されます。Selector を使用した非同期 I/O の場合はこの通知を使用して
 * SelectionKey.OP_WRITE を制御する事が出来ます。
 *
 * キューに投入するメッセージの優先順位付けは行われません。
 * {@link org.asterisque.Control} はキューの先頭へ優先的に追加されます。
 *
 * @author Takami Torao
 */
class MessageQueue {
	private static final Logger logger = LoggerFactory.getLogger(MessageQueue.class);

	/**
	 * このキューから入力側へ通知を行うメッセージ数。キューの保持するメッセージがこの数に達すると通知が行われます。
	 */
	public final int softLimit;

	/**
	 * このキューが一度に保持することの出来るメッセージの最大数。この数を超えてメッセージの追加を行おうとした場合、
	 * 例外が発生し Wire がクローズされます。
	 */
	public final int hardLimit;

	/**
	 * メッセージキュー。
	 */
	private final BlockingQueue<Entry> queue;

	/**
	 * キューへのメッセージ追加や取り出しが行われたときに呼び出される関数。第一引数は enqueue の場合に true、
	 * dequeue の場合に false。第二引数はキュー変更後のサイズ。
	 */
	private final BiConsumer<Boolean,Integer> onChange;

	/**
	 * この Wire で送信されるメッセージのシーケンス番号。同一優先順のメッセージ順序を維持するために使用する。
	 * 有限だが 1sec に 1000 メッセージ enqueue するとしても全てを消費するまで 557 MegaYear が必要であるため、
	 * 上限に達したら例外を送出する (このキューを使用する Wire がクローズされる) ことで対処する。
	 */
	private final AtomicLong sequence = new AtomicLong(Long.MIN_VALUE);

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 *
	 * @param softLimit メッセージ追加時に onSoftLimit.accept(true) が実行されるメッセージ数
	 * @param hardLimit メッセージ追加時にブロックが発生するメッセージ数
	 * @param onSoftLimit キューの保持するメッセージ数が {@code softLimit} に達した時 true、{@code
	 *                        softLimit} より小さくなった時 false で呼び出されるコールバック関数
	 * @param onEmpty キューの保持するメッセージ数が 0 になった時 true、0 から 1 になった時 false で呼び出され
	 *                るコールバック関数
	 */
	public MessageQueue(int softLimit, int hardLimit, Consumer<Boolean> onSoftLimit, Consumer<Boolean> onEmpty){
		this(softLimit, hardLimit, (enqueue, size) -> {
			if(enqueue){
				if(size == 1){
					onEmpty.accept(false);
				}
				if(size == softLimit){
					onSoftLimit.accept(true);
				}
			} else {
				if(size == 0){
					onEmpty.accept(true);
				}
				if(size == softLimit - 1){
					onSoftLimit.accept(false);
				}
			}
		});
	}

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 *
	 * @param softLimit メッセージ追加時に onAdvisoryLimit.accept(true) が実行されるメッセージ数
	 * @param hardLimit メッセージ追加時にブロックが発生するメッセージ数
	 * @param onAdvisoryLimit キューの保持するメッセージ数が {@code softLimit} に達した時 true、{@code
	 *                        softLimit} より小さくなった時 false で呼び出されるコールバック関数
	 * @param onEnqueue メッセージがキューに投入された後に毎回呼び出されるコールバック関数。
	 */
	public MessageQueue(int softLimit, int hardLimit, Consumer<Boolean> onAdvisoryLimit, Runnable onEnqueue){
		this(softLimit, hardLimit, (enqueue, size) -> {
			if(enqueue){
				onEnqueue.run();
				if(size == softLimit){
					onAdvisoryLimit.accept(true);
				}
			} else {
				if(size == softLimit - 1){
					onAdvisoryLimit.accept(false);
				}
			}
		});
	}

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 *
	 * @param softLimit メッセージ追加時に onAdvisoryLimit.accept(true) が実行されるメッセージ数
	 * @param hardLimit メッセージ追加時にブロックが発生するメッセージ数
	 * @param onChange キューへのメッセージ追加や取り出しが発生すると呼び出されるコールバック関数。
	 */
	private MessageQueue(int softLimit, int hardLimit, BiConsumer<Boolean,Integer> onChange){
		if(softLimit > hardLimit){
			throw new IllegalArgumentException(
				String.format("advisory limit exceeds blocking limit: %d > %d", softLimit, hardLimit));
		}
		this.softLimit = softLimit;
		this.hardLimit = hardLimit;
		this.queue = new PriorityBlockingQueue<>(softLimit);

		this.onChange = onChange;
	}

	// ==============================================================================================
	// メッセージの追加
	// ==============================================================================================
	/**
	 * このメッセージキューに指定されたメッセージを追加します。メッセージは {@link org.asterisque.Message#priority
	 * 優先順位} よってキュー内で優先順位付けが行われます。同一の優先順位を持つメッセージの順序が逆転することはあり
	 * ません。
	 *
	 * このキューが空だった場合は onEmpty(false) コールバックが発生します。
	 * キューが保持するメッセージ数が soft limit に達した場合は onSoft(true) が発生します。
	 * この呼び出しで hard limit + 1 に達する場合は空きが出来るまで呼び出し側のスレッドをブロックします。
	 *
	 * @param msg キューに投入するメッセージ
	 * @throws java.lang.InterruptedException ブロック中に呼び出しスレッドが割り込まれた場合
	 */
	public void enqueue(byte priority, Message msg) throws HardLimitReached, MaxSequenceReached {
		long seq = sequence.getAndIncrement();
		if(seq == Long.MAX_VALUE){
			sequence.decrementAndGet();
			throw new MaxSequenceReached();
		}
		Entry entry = new Entry(msg, priority, seq);
		int size;
		synchronized(queue){
			queue.add(entry);
			size = queue.size();
		}
		if(size + 1 == hardLimit){
			logger.error("hard limit reached: " + hardLimit);
			throw new HardLimitReached();
		}
		onChange.accept(true, size);
	}

	// ==============================================================================================
	// メッセージの取り出し
	// ==============================================================================================
	/**
	 * このキューの先頭からブロッキングなしでメッセージを取り出します。このキューが有効なメッセージを持たない場合は
	 * Optional.empty() を返します。
	 *
	 * この呼び出しによりキューが空になった場合は onEmpty(true) 通知が発生します。
	 * またキューが保持するメッセージ数が soft limit - 1 に達した場合は onSoftLimit(false) が発生します。
	 *
	 * @return 取り出したメッセージ、またはキューが空の場合は Optional.empty()
	 */
	public Optional<Message> dequeue() {
		Entry entry;
		int size;
		synchronized(queue){
			if(queue.isEmpty()){
				return Optional.empty();
			} else {
				entry = queue.remove();
				size = queue.size();
			}
		}
		onChange.accept(false, size);
		return Optional.of(entry.msg);
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
			if(this.priority > other.priority) return -1;
			if(this.priority < other.priority) return 1;
			if(this.sequence < other.sequence) return -1;
			if(this.sequence > other.sequence) return 1;
			return 0;		// this == other 以外で同一順位の存在はありえないので例外にしても良いかもしれない
		}
	}

	public static class HardLimitReached extends Exception { }
	public static class MaxSequenceReached extends Exception { }

}
