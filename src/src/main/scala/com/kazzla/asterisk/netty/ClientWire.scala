/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.netty

import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.bootstrap.{ClientBootstrap, Bootstrap}
import org.jboss.netty.channel.Channel
import com.kazzla.asterisk.{Message, Wire}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// ClientEndpoint
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class ClientWire(factory:AsteriskPipelineFactory) extends Wire {
	val (bootstrap:Bootstrap, channel:Channel) = {
		val client = new ClientBootstrap(new NioClientSocketChannelFactory())
		client.setPipelineFactory(factory)
		val future = client.connect(address)
		future.awaitUninterruptibly()
		if(! future.isSuccess){
			throw future.getCause
		}
		(client, future.getChannel)
	}

	def send(m:Message):Unit = {

	}
}
