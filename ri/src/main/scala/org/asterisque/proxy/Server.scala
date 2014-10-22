/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.proxy

import java.net.SocketAddress
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext

import org.asterisque._
import org.asterisque.cluster.Repository

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Server
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class Server(
	val options:Options,
	val cert:X509Certificate, name:String, address:SocketAddress
) {
	val sslContext = options.get(Options.KEY_SSL_CONTEXT)

	val id = UUID.fromString(cert.getSubjectX500Principal.getName)

	private[this] val exec = Executors.newCachedThreadPool()

	def start():Unit = {
		val proxy = new Node(id, name, exec, Service, Repository.OnMemory)
		proxy.listen(address, new Options()){ session =>
			session.
		}
	}
}

object Server {
	def main(args:Array[String]):Unit = {

	}
}
