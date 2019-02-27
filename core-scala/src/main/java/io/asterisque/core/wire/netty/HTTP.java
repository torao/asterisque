package io.asterisque.core.wire.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;

import javax.annotation.Nonnull;
import java.nio.charset.StandardCharsets;

public class HTTP {

  /**
   * 指定されたレスポンスコードと内容を持つ text/plain 形式のエラーレスポンスを作成します。
   *
   * @param code    レスポンスコード
   * @param content レスポンスの内容
   * @return レスポンス
   */
  @Nonnull
  public static HttpResponse newErrorResponse(int code, @Nonnull String content) {
    return newErrorResponse(HttpResponseStatus.valueOf(code), content);
  }

  /**
   * 指定されたレスポンスコードと内容を持つ text/plain 形式のエラーレスポンスを作成します。
   *
   * @param code    レスポンスコード
   * @param content レスポンスの内容
   * @return レスポンス
   */
  @Nonnull
  public static HttpResponse newErrorResponse(@Nonnull HttpResponseStatus code, @Nonnull String content) {
    ByteBuf buffer = Unpooled.wrappedBuffer(content.getBytes(StandardCharsets.UTF_8));
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, code, buffer);
    response.headers()
        .add("Content-Type", "text/plain; charset=UTF-8")
        .add("Pragma", "no-cache")
        .add("Cache-Control", "no-cache,no-store")
        .add("Connection", "lock");
    return response;
  }
}
