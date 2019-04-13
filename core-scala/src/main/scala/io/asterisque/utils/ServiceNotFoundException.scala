package io.asterisque.utils

import javax.annotation.{Nonnull, Nullable}

class ServiceNotFoundException(@Nonnull message:String, @Nullable ex:Throwable) extends RuntimeException(message, ex) {
  def this(@Nonnull message:String) = this(message, null)

  def this(@Nonnull ex:Throwable) = this("", ex)

  def this() = this("", null)
}
