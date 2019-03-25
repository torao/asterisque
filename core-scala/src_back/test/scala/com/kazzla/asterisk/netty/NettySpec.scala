/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.netty

import com.kazzla.asterisk.{Wire, BridgeWireSpec}
import java.io.File

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// NettySpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class NettySpec {
}

class NettyWireSpec extends BridgeWireSpec(
//  None, None
  Some(Wire.loadSSLContext(new File("ca/client.jks"), "kazzla", "kazzla", new File("ca/cacert.jks"), "kazzla")),
  Some(Wire.loadSSLContext(new File("ca/server.jks"), "kazzla", "kazzla", new File("ca/cacert.jks"), "kazzla"))
) {
  def bridge = Netty
}