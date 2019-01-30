package io.asterisque.core.session;

import io.asterisque.core.ProtocolException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * プロトコル違反を検知したときに発生する例外です。
 */
public class ProtocolViolationException extends ProtocolException {
  public ProtocolViolationException(@Nonnull String message) {
    super(message);
  }

  public ProtocolViolationException(@Nonnull String message, @Nullable Throwable ex) {
    super(message, ex);
  }
}
