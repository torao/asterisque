/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.net.SocketAddress
import javax.net.ssl.SSLContext
import scala.concurrent.Future
import java.io.Closeable
import com.kazzla.asterisk.codec.Codec

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// NetworkDriver
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
trait Bridge {
	def connect(codec:Codec, address:SocketAddress, sslContext:Option[SSLContext]):Future[Wire]
	def listen(codec:Codec, address:SocketAddress, sslContext:Option[SSLContext])(onAccept:(Wire)=>Unit):Future[Server]
}

class Server(val address:SocketAddress) extends Closeable {
	def close():Unit = None
}