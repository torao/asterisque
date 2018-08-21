/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import org.specs2.Specification

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// EventHandlerSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class EventHandlerSpec extends Specification { def is = s2"""
EventHandler should:
append and remove handlers. $e0
ignore any exceptions other than ThreadDeath from handler. $e1
"""

	def e0 = {
		var called = 0
		val h = Seq[(Int)=>Unit](
			{ i => called += 1 },
			{ i => called += 2 },
			{ i => called += 3 }
		)

		val handlers = new EventHandlers[Int]()
		handlers ++ h(0)
		handlers ++ h(1)
		handlers ++ h(2)
		handlers(0)
		val six = called

		called = 0
		handlers -- h(0)
		handlers(0)
		val five = called

		// 複数回の呼び出しは無視される
		handlers -- h(0)

		called = 0
		handlers -- h(1)
		handlers(0)
		val three = called

		called = 0
		handlers -- h(2)
		handlers(0)
		val zero = called

		// 未登録のハンドラは無視される
		handlers -- { i => None }

		(six === 6) and (five === 5) and (three === 3) and (zero === 0)
	}

	def e1 = {
		val h1 = new EventHandlers[Int]() ++ { _ => throw new RuntimeException() } ++ { _ => throw new OutOfMemoryError() }
		val h2 = new EventHandlers[Int]() ++ { _ => throw new ThreadDeath() }
		h1(0)
		try {
			h2(0)
			failure
		} catch {
			case ex:ThreadDeath => success
		}
	}
}
