/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Priority
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

import io.asterisque.msg.Open;

/**
 * {@link org.asterisque.Pipe} ごとに設定するメッセージの優先度定数とユーティリティ機能を定義しています。
 *
 * @see Open#priority
 * @author Takami Torao
 */
public final class Priority {
	/** 最も高い優先度を示す定数 */
	public static final byte Max = Byte.MAX_VALUE;
	/** 最も低い優先度を示す定数 */
	public static final byte Min = Byte.MIN_VALUE;
	/** 通常の優先度を示す定数 */
	public static final byte Normal = 0;
	/** コンストラクタはクラス内に隠蔽されています */
	private Priority(){ }
	/** 指定された優先度を一つ高い値に変換します */
	public static byte upper(byte priority){ return (byte)Math.min(priority + 1, Max); }
	/** 指定された優先度を一つ低い値に変換します */
	public static byte lower(byte priority){ return (byte)Math.max(priority - 1, Min); }
}
