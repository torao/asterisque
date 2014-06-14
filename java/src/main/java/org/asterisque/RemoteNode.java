/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// RemoteNode
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * リモートノードを表す不変クラスです。
 *
 * @author Takami Torao
 */
public class RemoteNode {
	public final SocketAddress address;
	public RemoteNode(SocketAddress address){
		assert(address != null);
		this.address = address;
	}
	@Override
	public String toString(){
		if(address instanceof InetSocketAddress){
			InetSocketAddress i = (InetSocketAddress)address;
			return i.getAddress().getHostAddress() + ":" + i.getPort();
		}
		return address.toString();
	}
}
