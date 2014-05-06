/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque;

import java.util.Optional;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// MessageQueue
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * 非同期 I/O と asterisque のフレームワーク間でメッセージの中継を行うためのキューです。
 * {@link io.asterisque.Control} はキューの先頭へ優先的に追加されます。
 *
 * キューは保持するメッセージ数が advisory limit に達すると入力側に advisoryLimit(true) 要求を出します。ただ
 * し具体的な制限はなく単に通知が発生するのみです。入力側は advisoryLimit(true) 通知を受け取った場合、次に
 * suspend(false) 通知を受け取るまで新しいメッセージを投入すべきではありません。メッセージ数が advisory limit
 * を超え blocking limit に達すると、キューに空きが出来るまで enqueue() の呼び出しが強制的にブロックされます。
 *
 * 同様に、全てのメッセージがキューから読み出されると出力側は empty(true) 通知を受け取ります。この後に新しく
 * メッセージが到着すると empty(false) が通知されます。Selector を使用した非同期 I/O の場合はこの通知を使用して
 * SelectionKey.OP_WRITE を制御する事が出来ます。
 *
 * @author Takami Torao
 */
class MessageQueue {

	/**
	 * このキューから入力側へ通知を行うメッセージ数。キューの保持するメッセージがこの数に達すると通知が行われます。
	 */
	public final int advisoryLimit;

	/**
	 * このキューが一度に保持することの出来るメッセージの最大数。この数を超えてメッセージの追加を行おうとした場合、
	 * 処理がブロックされます。
	 */
	public final int blockingLimit;

	/**
	 * メッセージキュー。
	 */
	private final BlockingDeque<Message> queue;

	/**
	 * キューへのメッセージ追加や取り出しが行われたときに呼び出される関数。enqueue の場合第一引数が true。
	 * 第二引数はキュー操作結果としてのキューのサイズ。
	 */
	private final BiConsumer<Boolean,Integer> onChange;

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 *
	 * @param advisoryLimit メッセージ追加時に onAdvisoryLimit.accept(true) が実行されるメッセージ数
	 * @param blockingLimit メッセージ追加時にブロックが発生するメッセージ数
	 * @param onAdvisoryLimit キューの保持するメッセージ数が {@code advisoryLimit} に達した時 true、{@code
	 *                        advisoryLimit} より小さくなった時 false で呼び出されるコールバック関数
	 * @param onEmpty キューの保持するメッセージ数が 0 になった時 true、0 から 1 になった時 false で呼び出され
	 *                るコールバック関数
	 */
	public MessageQueue(int advisoryLimit, int blockingLimit, Consumer<Boolean> onAdvisoryLimit, Consumer<Boolean> onEmpty){
		this(advisoryLimit, blockingLimit, (enqueue, size) -> {
			if(enqueue){
				if(size == 1){
					onEmpty.accept(false);
				}
				if(size == advisoryLimit){
					onAdvisoryLimit.accept(true);
				}
			} else {
				if(size == 0){
					onEmpty.accept(true);
				}
				if(size == advisoryLimit - 1){
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
	 * @param advisoryLimit メッセージ追加時に onAdvisoryLimit.accept(true) が実行されるメッセージ数
	 * @param blockingLimit メッセージ追加時にブロックが発生するメッセージ数
	 * @param onAdvisoryLimit キューの保持するメッセージ数が {@code advisoryLimit} に達した時 true、{@code
	 *                        advisoryLimit} より小さくなった時 false で呼び出されるコールバック関数
	 * @param onEnqueue メッセージがキューに投入された後に毎回呼び出されるコールバック関数。
	 */
	public MessageQueue(int advisoryLimit, int blockingLimit, Consumer<Boolean> onAdvisoryLimit, Runnable onEnqueue){
		this(advisoryLimit, blockingLimit, (enqueue, size) -> {
			if(enqueue){
				onEnqueue.run();
				if(size == advisoryLimit){
					onAdvisoryLimit.accept(true);
				}
			} else {
				if(size == advisoryLimit - 1){
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
	 * @param advisoryLimit メッセージ追加時に onAdvisoryLimit.accept(true) が実行されるメッセージ数
	 * @param blockingLimit メッセージ追加時にブロックが発生するメッセージ数
	 * @param onChange キューへのメッセージ追加や取り出しが発生すると呼び出されるコールバック関数。
	 */
	private MessageQueue(int advisoryLimit, int blockingLimit, BiConsumer<Boolean,Integer> onChange){
		if(advisoryLimit > blockingLimit){
			throw new IllegalArgumentException(
				String.format("advisory limit exceeds blocking limit: %d > %d", advisoryLimit, blockingLimit));
		}
		this.advisoryLimit = advisoryLimit;
		this.blockingLimit = blockingLimit;
		this.queue = new LinkedBlockingDeque<>(blockingLimit);

		this.onChange = onChange;
	}

	// ==============================================================================================
	// メッセージの追加
	// ==============================================================================================
	/**
	 * このキューの末尾に指定されたメッセージを追加します。ただしメッセージが {@link io.asterisque.Control} の
	 * 場合はキューの先頭に投入されます。
	 *
	 * このキューが空だった場合は onEmpty(false) コールバックが発生します。
	 * キューが保持するメッセージ数が advisory limit に達した場合は onAdvisoryLimit(true) が発生します。
	 * この呼び出しで blocking limit + 1 に達する場合は空きが出来るまで呼び出し側のスレッドをブロックします。
	 *
	 * @param msg キューに投入するメッセージ
	 * @throws java.lang.InterruptedException ブロック中に呼び出しスレッドが割り込まれた場合
	 */
	public void enqueue(Message msg) throws InterruptedException {
		int size;
		synchronized(queue){
			if(msg instanceof Control){
				queue.putFirst(msg);
			} else {
				queue.putLast(msg);
			}
			size = queue.size();
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
	 * この呼び出しによりキューが空になった場合は onEmpty(true) が発生します。
	 * キューが保持するメッセージ数が advisory limit - 1 に達した場合は onAdvisoryLimit(false) が発生します。
	 * この呼び出しで blocking limit より小さい数になった場合はブロックされていた enqueue() 処理が再開します。
	 *
	 * @return 取り出したメッセージ、またはキューが空の場合は Optional.empty()
	 */
	public Optional<Message> dequeue() {
		Message msg;
		int size;
		synchronized(queue){
			if(queue.isEmpty()){
				return Optional.empty();
			} else {
				msg = queue.removeFirst();
				size = queue.size();
			}
		}
		onChange.accept(false, size);
		return Optional.of(msg);
	}

}
