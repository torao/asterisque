package io.asterisque.wire

import javax.annotation.{Nonnull, Nullable}

class AuthenticationException(@Nonnull message:String, @Nullable ex:Throwable) extends ProtocolException(message, ex) {
  def this(@Nonnull message:String) = this(message, null)
}
