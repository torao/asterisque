package io.asterisque.core.session;

import io.asterisque.core.ProtocolException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * ピアの指定した function が見つからないときに発生する例外です。
 */
public class NoSuchFunctionException extends ProtocolException {
  public NoSuchFunctionException(@Nonnull String message) {
    super(message);
  }

  public NoSuchFunctionException(@Nonnull String message, @Nullable Throwable ex) {
    super(message, ex);
  }
}
