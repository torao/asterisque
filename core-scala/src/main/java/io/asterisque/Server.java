package io.asterisque;

import java.io.Closeable;
import java.net.SocketAddress;

/**
 * {@link Bridge#newServer(Node, java.net.SocketAddress, Options, java.util.function.Consumer)}
 * によって生成されるサーバをクローズするために使用するクラスです。
 */
public abstract class Server implements Closeable {
  public final Node node;
  public final SocketAddress address;
  public final Options options;

  protected Server(Node node, SocketAddress address, Options options) {
    this.node = node;
    this.address = address;
    this.options = options;
  }

  public abstract void close();
}
