/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

import org.asterisque.msg.Block;
import org.asterisque.util.Source;

import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// PipeMessageSource
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * パイプに対してブロック送受信 (ストリーム) 操作をおこなうためのクラス。
 * メッセージ配信スレッド内でハンドラを設定する必要があります。
 */
final class PipeMessageSource extends Source<Block> implements Consumer<Block> {
	private final Pipe pipe;

	public PipeMessageSource(Pipe pipe) { this.pipe = pipe; }

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
		pipe.assertInCall("operation for message passing can only define in caller thread");
		super.onAddOperation();
	}

}