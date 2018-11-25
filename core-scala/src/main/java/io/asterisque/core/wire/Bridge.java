package io.asterisque.core.wire;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Bridge は下層のメッセージングネットワーク実装を利用するためのインターフェース。別のノードへの接続と、別のノードからの
 * 接続受付を実装する。
 *
 * @author Takami Torao
 */
public interface Bridge extends AutoCloseable {

  /**
   * 指定されたリモートノードに対して非同期接続を行い {@link Wire} の Future を返します。
   * ソケットオプションのようなプロトコル固有のオプションは URI のクエリーで指定することができます。
   *
   * @param uri         接続先の URI
   * @param subprotocol asterisque 上で実装しているサブプロトコル
   * @param sslContext  Secure ソケットを示す URI スキーマが指定された場合 (例えば {@code wss://}) に使用する SSL コンテキスト
   * @return Wire の Future
   */
  CompletableFuture<Wire> newWire(@Nonnull URI uri, @Nonnull String subprotocol,
                                  int inboundQueueSize, int outboundQueueSize, @Nullable SSLContext sslContext);

  default CompletableFuture<Wire> newWire(@Nonnull URI uri, @Nonnull String subprotocol,
                                          int inboundQUeueSize, int outboundQUeueSize) {
    return newWire(uri, subprotocol, inboundQUeueSize, outboundQUeueSize, null);
  }

  default CompletableFuture<Wire> newWire(@Nonnull URI uri, @Nonnull String subprotocol) {
    return newWire(uri, subprotocol, Short.MAX_VALUE, Short.MAX_VALUE, null);
  }

  /**
   * 指定されたネットワークからの接続を非同期で受け付ける `Server` の Future を返します。
   *
   * @param uri         接続先の URI
   * @param subprotocol asterisque 上で実装しているサブプロトコル
   * @param sslContext  Secure ソケットを示す URI スキーマが指定された場合 (例えば {@code wss://}) に使用する SSL コンテキスト
   * @param onAccept    サーバが接続を受け付けたときのコールバック
   */
  CompletableFuture<Server> newServer(@Nonnull URI uri, @Nonnull String subprotocol,
                                      int inboundQueueSize, int outboundQueueSize,
                                      @Nullable SSLContext sslContext,
                                      @Nonnull Consumer<CompletableFuture<Wire>> onAccept);

  default CompletableFuture<Server> newServer(@Nonnull URI uri, @Nonnull String subprotocol,
                                      @Nullable SSLContext sslContext,
                                      @Nonnull Consumer<CompletableFuture<Wire>> onAccept){
    return newServer(uri, subprotocol, Short.MAX_VALUE, Short.MAX_VALUE, sslContext, onAccept);
  }

}
