package io.asterisque.core.wire.netty

import io.asterisque.core.wire.{Bridge, WebSocketBridgeSpec}

class NettyBridgeWebSocketSpec extends WebSocketBridgeSpec {
  override def newBridge[T]():Bridge[T] = new NettyBridge[T]
}
