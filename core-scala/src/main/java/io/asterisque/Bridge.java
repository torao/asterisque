package io.asterisque;

import io.asterisque.netty.Netty;

import java.net.SocketAddress;
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
   * デフォルトで使用されるブリッジのインスタンスです。
   */
  Bridge DefaultBridge = new Netty();

  /**
   * 指定されたリモートノードに対して非同期接続を行い `Wire` の Future を返します。
   * TODO SocketAddress を URI に変更
   *
   * @param local   ローカル側のノード
   * @param address ソケットアドレス
   * @param options 接続設定
   * @return Wire の Future
   */
  CompletableFuture<Wire> newWire(Node local, SocketAddress address, Options options);

  /**
   * 指定されたネットワークからの接続を非同期で受け付ける `Server` の Future を返します。
   * TODO SocketAddress を URI に変更
   *
   * @param options 接続設定
   * @return Server の Future
   */
  CompletableFuture<Server> newServer(Node local, SocketAddress address, Options options, Consumer<Wire> onAccept);

  /**
   * このインスタンスをクローズしリソースを解放します。
   */
  void close();

}
