/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.util

import java.util.concurrent.Executors

import org.specs2.Specification

import scala.concurrent.ExecutionContext

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// CircuitBreakerSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class CircuitBreakerSpec extends Specification { def is = s2"""
CircuitBreaker should:
notify at soft-limit and hard-limit. $e0
be able to call in multi-threads. $e1
"""

	def e0 = {
		var b = false
		var ol = 0
		val breaker = new CircuitBreaker(2, 4) {
			override protected def overload(overload:Boolean):Unit = ol += (if(overload) 1 else -1)
			override protected def broken():Unit = b = true
		}
		(breaker.isBroken must beFalse) and
			{breaker.increment(); breaker.isBroken must beFalse} and (ol === 0) and     // 1
			{breaker.increment(); breaker.isBroken must beFalse} and (ol === 1) and     // 2
			{breaker.increment(); breaker.isBroken must beFalse} and (ol === 1) and     // 3
			{breaker.decrement(); breaker.isBroken must beFalse} and (ol === 1) and     // 2
			{breaker.decrement(); breaker.isBroken must beFalse} and (ol === 0) and     // 1
			{breaker.increment(); breaker.increment(); breaker.increment(); breaker.isBroken must beTrue} and
			(breaker.load() === 4) and (b must beTrue) and (ol === 1) and (breaker.overloadCount() === 2)
	}

	def e1 = {
		var br = 0
		var ol = 0
		val breaker = new CircuitBreaker(1, 11) {
			override protected def overload(overload:Boolean):Unit = ol += (if(overload) 1 else -1)
			override protected def broken():Unit = br += 1
		}

		val threads = Executors.newCachedThreadPool()
		implicit val context = ExecutionContext.fromExecutor(threads)
		(0 until 20).par.foreach{ i =>
			if((i & 0x01) == 0) {
				breaker.increment()
			} else {
				breaker.decrement()
			}
		}

		(breaker.isBroken must beFalse) and (breaker.load() === 0)
	}

}
