/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.net.SocketAddress
import javax.net.ssl.SSLContext
import scala.concurrent.{Promise, Future}
import java.io.Closeable
import scala.util.{Failure, Success}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// NetworkDriver
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
trait NetworkDriver {
	def connect(address:SocketAddress, sslContext:Option[SSLContext]):Future[Wire]
	def listen(address:SocketAddress, sslContext:Option[SSLContext])(onAccept:(Wire)=>Unit):Server

	def listen(address:SocketAddress, sslContext:Option[SSLContext], node:Node):NetworkDriver = {
		listen(address, sslContext){ wire => node.connect(wire) }
		this
	}

	def connect(address:SocketAddress, sslContext:Option[SSLContext], node:Node):Future[Session] = {
		import scala.concurrent.ExecutionContext.Implicits.global
		val promise = Promise[Session]()
		connect(address, sslContext).onComplete {
			case Success(wire) => promise.success(node.connect(wire))
			case Failure(ex) => promise.failure(ex)
		}
		promise.future
	}

}

class Server(val address:SocketAddress) extends Closeable {
	def close():Unit = None
}