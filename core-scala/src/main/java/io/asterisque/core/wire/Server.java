package io.asterisque.core.wire;

import javax.annotation.Nullable;
import java.net.SocketAddress;

public interface Server extends AutoCloseable {

  @Nullable
  SocketAddress bindAddress();

  void close();
}
