/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.message;

import java.io.Serializable;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Message
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 *
 * @author Takami Torao
 */
public abstract class Message implements Serializable {

	// ==============================================================================================
	// パイプ ID
	// ==============================================================================================
	/**
	 * このメッセージの宛先を示すパイプ ID です。
	 */
	public final short pipeId;

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 * 同一パッケージ内のサブクラスからのみ構築することが出来ます。
	 */
	Message(short pipeId){
		if(pipeId == 0 && ! (this instanceof Control)){
			throw new IllegalArgumentException("pipe-id should be zero if only Control message: " + pipeId);
		}
		this.pipeId = pipeId;
	}
}
