/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.sample;

import io.asterisque.Export;
import io.asterisque.LocalNode;
import io.asterisque.Service;
import io.asterisque.conf.ListenConfig;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// EchoServer
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
public class EchoServer extends Service {
	@Export(10)
	public String echo(String value){ return value; }
	public static void main(String[] args) {
		LocalNode local = new LocalNode("Echo Client", new EchoServer());
		local.listen(new ListenConfig()
			.);
	}
}
