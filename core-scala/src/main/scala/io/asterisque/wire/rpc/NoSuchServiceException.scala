package io.asterisque.wire.rpc

import io.asterisque.wire.ProtocolException
import javax.annotation.{Nonnull, Nullable}

class NoSuchServiceException(@Nullable message:String, @Nullable ex:Throwable) extends ProtocolException(message, ex) {
  def this(@Nonnull message:String) = this(message, null)

  def this(@Nonnull ex:Throwable) = this("", ex)

  def this() = this("", null)
}
