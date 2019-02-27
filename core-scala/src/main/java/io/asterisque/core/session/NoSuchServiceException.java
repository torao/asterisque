package io.asterisque.core.session;

import io.asterisque.wire.ProtocolException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * ピアの指定したサービスが見つからないときに発生する例外です。
 */
public class NoSuchServiceException extends ProtocolException {
  public NoSuchServiceException(@Nonnull String message) {
    super(message);
  }

  public NoSuchServiceException(@Nonnull String message, @Nullable Throwable ex) {
    super(message, ex);
  }
}
