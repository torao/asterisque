/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/

import io.asterisque._

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Sample1
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
object Sample1 {
	def main(args:Array[String]):Unit = {
		val local = new LocalNode()
		val remote = new Node()

		local.::(8089) <- global : { session =>
		}

		local -> node : 8089 :: { session =>

		}
	}
}
