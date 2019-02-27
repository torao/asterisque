package io.asterisque.wire.message;

import io.asterisque.ProtocolViolationException;

/**
 * メッセージのエンコード/デコードに失敗した事を表す例外です。
 */
public class CodecException extends ProtocolViolationException {
  /**
   * @param msg 例外メッセージ
   * @param ex 下層の例外
   */
  public CodecException(String msg, Throwable ex){ super(msg, ex); }

  /**
   * @param msg 例外メッセージ
   */
  public CodecException(String msg){ super(msg); }
}
