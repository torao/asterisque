package io.asterisque.wire.gateway.netty

import java.nio.charset.StandardCharsets

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http._
import javax.annotation.Nonnull


object HTTP {
  /**
    * 指定されたレスポンスコードと内容を持つ text/plain 形式のエラーレスポンスを作成します。
    *
    * @param code    レスポンスコード
    * @param content レスポンスの内容
    * @return レスポンス
    */
  @Nonnull
  def newErrorResponse(@Nonnull code:HttpResponseStatus, @Nonnull content:String):HttpResponse = {
    val buffer = Unpooled.wrappedBuffer(content.getBytes(StandardCharsets.UTF_8))
    val response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, code, buffer)
    response.headers.add("Content-Type", "text/plain; charset=UTF-8").add("Pragma", "no-cache").add("Cache-Control", "no-cache,no-store").add("Connection", "lock")
    response
  }
}
