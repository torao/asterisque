package io.asterisque.core.wire;

import javax.annotation.Nonnull;

/**
 * 新しい Wire や Server を構築するときの URI Schema が認識できない時に発生する例外です。
 */
public class UnsupportedProtocolException extends RuntimeException {
  public UnsupportedProtocolException(@Nonnull String message) {
    super(message);
  }

  public UnsupportedProtocolException(@Nonnull String message, Throwable ex) {
    super(message, ex);
  }
}
