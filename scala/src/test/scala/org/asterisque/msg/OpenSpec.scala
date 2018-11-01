/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.msg

import java.lang.reflect.Modifier

import org.specs2.Specification

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// OpenSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class OpenSpec extends Specification { def is = s2"""
Open should:
declare as final class. ${Modifier.isFinal(classOf[Open].getModifiers) must beTrue}
have properties these are specified in constructor. $e0
throw NullPointerException if data is null. ${new Open(1, 8, 12, null) must throwA[NullPointerException]}
"""
	def e0 = {
		val args:Array[AnyRef] = Array("A", Integer.valueOf(2))
		val o = new Open(1, 8, 12, args)
		(o.pipeId === 1) and (o.priority === 8) and (o.functionId === 12) and o.params.zip(args).map{ case (a,b) => a === b }.reduce{ _ and _ }
	}
}
