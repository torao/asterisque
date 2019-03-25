package io.asterisque.wire.rpc

import java.io.{InputStream, OutputStream}
import java.lang.reflect.Method

import javax.annotation.{Nonnull, Nullable}

/**
  * メソッドのパラメータまたは
  */
trait Codec {

  @throws[CodecException]
  def encode(@Nonnull out:OutputStream, @Nonnull method:Method, isParams:Boolean, @Nullable value:Object):Unit

  @throws[CodecException]
  def decode(@Nonnull in:InputStream, @Nonnull method:Method, isParams:Boolean):Object
}
