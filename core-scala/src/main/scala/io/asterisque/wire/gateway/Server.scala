package io.asterisque.wire.gateway

import java.net.{SocketAddress, URI}

import javax.annotation.{Nonnull, Nullable}

trait Server extends AutoCloseable {

  @Nonnull
  def acceptURI:URI

  override def close():Unit
}
