package io.asterisque.core;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ProtocolException extends RuntimeException {
  public ProtocolException(@Nonnull String message) {
    super(message);
  }

  public ProtocolException(@Nonnull String message, @Nullable Throwable ex) {
    super(message, ex);
  }
}
