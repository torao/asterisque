package io.asterisque.core.wire;

import io.netty.handler.ssl.SslContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Bridge は下層のメッセージングネットワーク実装を利用するためのインターフェース。別のノードへの接続と、別のノードからの
 * 接続受付を実装する。
 *
 * @author Takami Torao
 */
public interface Bridge<NODE> extends AutoCloseable {

  /**
   * 指定されたリモートノードに対して非同期接続を行い {@link Wire} の Future を返します。
   * ソケットオプションのようなプロトコル固有のオプションは URI のクエリーで指定することができます。
   *
   * @param local       ローカル側のノード
   * @param uri         接続先の URI
   * @param subprotocol asterisque 上で実装しているサブプロトコル
   * @param sslContext  Secure ソケットを示す URI スキーマが指定された場合 (例えば {@code wss://}) に使用する SSL コンテキスト
   * @return Wire の Future
   */
  CompletableFuture<Wire<NODE>> newWire(@Nonnull NODE local, @Nonnull URI uri, @Nonnull String subprotocol,
                                        @Nullable SslContext sslContext);

  default CompletableFuture<Wire<NODE>> newWire(@Nonnull NODE local, @Nonnull URI uri, @Nonnull String subprotocol) {
    return newWire(local, uri, subprotocol, null);
  }

  /**
   * 指定されたネットワークからの接続を非同期で受け付ける `Server` の Future を返します。
   *
   * @param local       ローカル側のノード
   * @param uri         接続先の URI
   * @param subprotocol asterisque 上で実装しているサブプロトコル
   * @param sslContext  Secure ソケットを示す URI スキーマが指定された場合 (例えば {@code wss://}) に使用する SSL コンテキスト
   * @param onAccept    サーバが接続を受け付けたときのコールバック
   */
  CompletableFuture<Server<NODE>> newServer(@Nonnull NODE local, @Nonnull URI uri, @Nonnull String subprotocol,
                                            @Nullable SslContext sslContext,
                                            @Nonnull Consumer<CompletableFuture<Wire<NODE>>> onAccept);

}
