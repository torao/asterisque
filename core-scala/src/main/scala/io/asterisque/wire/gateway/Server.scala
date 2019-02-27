package io.asterisque.wire.gateway

import java.net.SocketAddress

import javax.annotation.Nullable

trait Server extends AutoCloseable {
  @Nullable
  def bindAddress:SocketAddress

  override def close():Unit
}
