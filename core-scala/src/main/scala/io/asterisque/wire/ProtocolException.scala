package io.asterisque.wire

import javax.annotation.{Nonnull, Nullable}

class ProtocolException(@Nonnull message:String, @Nullable ex:Throwable) extends RuntimeException(message, ex) {
  def this(@Nonnull message:String) = this(message, null)
}
