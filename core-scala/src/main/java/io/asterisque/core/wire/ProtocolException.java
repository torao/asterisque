package io.asterisque.core.wire;

import javax.annotation.Nonnull;

public class ProtocolException extends RuntimeException {
  public ProtocolException(@Nonnull String message) {
    super(message);
  }

  public ProtocolException(@Nonnull String message, Throwable ex) {
    super(message, ex);
  }
}
