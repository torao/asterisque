package io.asterisque.wire.message

import io.asterisque.wire.ProtocolException
import javax.annotation.{Nonnull, Nullable}

class Unsatisfied(@Nullable message:String, @Nullable ex:Throwable) extends CodecException(message, ex) {
  def this(@Nonnull message:String) = this(message, null)

  def this(@Nonnull ex:Throwable) = this("", ex)

  def this() = this("", null)
}
