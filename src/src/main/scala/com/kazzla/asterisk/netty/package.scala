/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import javax.net.ssl.{KeyManagerFactory, SSLContext}
import java.io.{FileInputStream, BufferedInputStream}

package object netty {

	def getSSLContext(file:String, ksPassword:String, pkPassword:String):SSLContext = {
		import java.security._
		val algorithm = Option(Security.getProperty("ssl.KeyManagerFactory.algorithm")).getOrElse("SunX509")
		val keyStore = KeyStore.getInstance("JKS")
		using(new BufferedInputStream(new FileInputStream(file))){ in =>
			keyStore.load(in, ksPassword.toCharArray)

			val kmf = KeyManagerFactory.getInstance(algorithm)
			kmf.init(keyStore, pkPassword.toCharArray)

			/*
			val tmf = TrustManagerFactory.getInstance(algorithm)
			tmf.init(keyStore)
			*/

			val context = SSLContext.getInstance("TLS")
			context.init(kmf.getKeyManagers, /* tmf.getTrustManagers */ null, null)
			context
		}
	}
}
