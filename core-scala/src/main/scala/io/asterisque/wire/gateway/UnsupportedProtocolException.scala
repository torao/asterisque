package io.asterisque.wire.gateway

import javax.annotation.{Nonnull, Nullable}
import io.asterisque.wire.ProtocolException

/**
  * 新しい Wire や Server を構築するときの URI Schema が認識できない時に発生する例外です。
  */
class UnsupportedProtocolException(@Nonnull message:String, @Nullable ex:Throwable) extends ProtocolException(message, ex) {
  def this(@Nonnull message:String) = this(message, null)
}
