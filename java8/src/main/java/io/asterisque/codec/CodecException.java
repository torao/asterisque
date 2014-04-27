/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.codec;

import io.asterisque.ProtocolViolationException;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// CodecException
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * メッセージのエンコード/デコードに失敗した事を表す例外です。
 */
public class CodecException extends ProtocolViolationException {
	/**
	 * @param msg 例外メッセージ
	 * @param ex 下層の例外
	 */
	public CodecException(String msg, Throwable ex){ super(msg, ex); }
	public CodecException(String msg){ super(msg); }
}
