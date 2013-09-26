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
import org.slf4j.LoggerFactory

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// NetworkDriver
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
trait NetworkDriver {
	import NetworkDriver._
	def connect(address:SocketAddress, sslContext:Option[SSLContext]):Future[Wire]
	def listen(address:SocketAddress, sslContext:Option[SSLContext])(onAccept:(Wire)=>Unit):Server

	def listen(address:SocketAddress, sslContext:Option[SSLContext], node:Node):NetworkDriver = {
		logger.debug(s"listening on ${address.toString} with TLS $sslContext on node: $node")
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

object NetworkDriver {
	private[NetworkDriver] val logger = LoggerFactory.getLogger(classOf[NetworkDriver])
}

class Server(val address:SocketAddress) extends Closeable {
	def close():Unit = None
}