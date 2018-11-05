/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// ProtocolViolationException
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * プロトコル違反を表す例外です。
 * @author Takami Torao
 */
public class ProtocolViolationException extends RuntimeException {
	/**
	 * @param msg 例外メッセージ
	 * @param ex 下層の例外
	 */
	public ProtocolViolationException(String msg, Throwable ex){ super(msg, ex); }
	public ProtocolViolationException(String msg){ super(msg); }
}
