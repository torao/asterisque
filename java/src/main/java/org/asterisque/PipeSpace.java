/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// PipeSpace
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * Pipe の生成と消滅を管理するクラス。
 * @author Takami Torao
 */
class PipeSpace {
	private static final Logger logger = LoggerFactory.getLogger(PipeSpace.class);

	/**
	 * 新規のパイプ ID を発行するためのシーケンス番号。
	 */
	private final AtomicInteger sequence = new AtomicInteger(0);

	/**
	 * 現在アクティブな Pipe のマップ。
	 */
	private final ConcurrentHashMap<Short,Pipe> pipes = new ConcurrentHashMap<>();

	/**
	 * クローズプロセスに入っているかの判定。
	 */
	private final AtomicBoolean closing = new AtomicBoolean(false);

	private final Session session;
	private final short pipeMask;

	public PipeSpace(Session session){
		this.session = session;
		this.pipeMask = session.isServer? Pipe.UniqueMask: 0;
	}

	// ==============================================================================================
	// パイプの参照
	// ==============================================================================================
	/**
	 * このパイプ空間から指定された ID のパイプを参照します。ID に該当するパイプが存在しない場合は empty() を返し
	 * ます。
	 */
	public Optional<Pipe> get(short pipeId){
		Pipe pipe = pipes.get(pipeId);
		if(pipe == null){
			return Optional.empty();
		}
		return Optional.of(pipe);
	}

	// ==============================================================================================
	// パイプの新規作成
	// ==============================================================================================
	/**
	 * ピアから受信した Open メッセージに対応するパイプを構築します。要求されたパイプ ID が既に使用されている場合は
	 * Optional.empty() を返します。
	 */
	public Optional<Pipe> create(Open open) {
		assert(! closing.get());
		// 新しいパイプを構築して登録
		Pipe newPipe = new Pipe(open.pipeId, open.priority, open.functionId, session);
		Pipe oldPipe = pipes.putIfAbsent(open.pipeId, newPipe);
		if(oldPipe != null) {
			// 既に使用されているパイプ ID が指定された場合はエラー
			logger.error("duplicate pipe-id specified: " + open.pipeId + "; " + oldPipe);
			return Optional.empty();
		}
		return Optional.of(newPipe);
	}

	// ==============================================================================================
	// パイプの新規作成
	// ==============================================================================================
	/**
	 * ピアに対して Open メッセージを送信するためのパイプを生成します。
	 */
	public Pipe create(byte priority, short function){
		while(true) {
			assert(! closing.get());
			short id = (short) ((sequence.getAndIncrement() & 0x7FFF) | pipeMask);
			if(id != 0){
				Pipe pipe = new Pipe(id, priority, function, session);
				if(pipes.putIfAbsent(id, pipe) == pipe){
					return pipe;
				}
			}
		}
	}

	// ==============================================================================================
	// パイプの破棄
	// ==============================================================================================
	/**
	 * ピアに対して Open メッセージを送信するためのパイプを生成します。
	 */
	public void destroy(short pipeId){
		pipes.remove(pipeId);
	}

	// ==============================================================================================
	// 全パイプのクローズ
	// ==============================================================================================
	/**
	 * このパイプ空間が保持している全てのパイプを破棄します。graceful に true を指定した場合はパイプに対して
	 * Close(Abort) メッセージを送信します。
	 *
	 * @param graceful Close メッセージ配信を行わず強制的に終了する場合 true
	 */
	public void close(boolean graceful){
		if(closing.compareAndSet(false, true) || ! graceful){
			if(graceful){
				// 残っているすべてのパイプに Close メッセージを送信
				pipes.values().forEach( pipe -> {
					Abort abort = new Abort(Abort.SessionClosing, "session " + session.id + " closing");
					pipe.close(new Close(pipe.id, abort));
				});
			}
			pipes.clear();
		}
	}

}
