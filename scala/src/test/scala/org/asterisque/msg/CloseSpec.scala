/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.msg

import java.lang.reflect.Modifier

import org.specs2.Specification

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// CloseSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class CloseSpec extends Specification { def is = s2"""
Close should:
declare as final. ${Modifier.isFinal(classOf[Close].getModifiers) must beTrue}
have properties that specified in constructor. $e0
create unexpected error. $e1
"""
	def e0 = {
		val c0 = new Close(1.toShort, "hoge")
		val c1 = new Close(2.toShort, new Abort(300, "foo"))
		(c0.result === "hoge") and (c0.abort must beNull) and
			(c1.abort !=== null) and (c1.abort.message === "foo")
	}

	def e1 = {
		val c = Close.unexpectedError(1, "error")
		(c.pipeId === 1) and (c.abort !=== null) and (c.abort.message === "error")
	}
}
