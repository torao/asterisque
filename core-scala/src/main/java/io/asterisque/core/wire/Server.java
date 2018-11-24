package io.asterisque.core.wire;

import io.asterisque.Node;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.net.SocketAddress;

public interface Server<NODE> extends Closeable {
  @Nonnull
  NODE node();

  @Nullable
  SocketAddress bindAddress();
}
