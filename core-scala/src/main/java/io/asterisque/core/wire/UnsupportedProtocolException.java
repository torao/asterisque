package io.asterisque.core.wire;

import io.asterisque.core.ProtocolException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * 新しい Wire や Server を構築するときの URI Schema が認識できない時に発生する例外です。
 */
public class UnsupportedProtocolException extends ProtocolException {
  public UnsupportedProtocolException(@Nonnull String message) {
    super(message);
  }

  public UnsupportedProtocolException(@Nonnull String message, @Nullable Throwable ex) {
    super(message, ex);
  }
}
