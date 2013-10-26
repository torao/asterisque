/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import org.specs2.Specification

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// WireSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
*/
abstract class WireSpec extends Specification { def is = s2"""
Wire should:
append and remove handlers. $e0
ignore any exceptions other than ThreadDeath from handler. $e1
"""

	def wire:Wire

	/**
	 * subclass should pair of transmission endpoint as tuple of wires.
	*/
	def wirePair:(Wire,Wire)

	def e0 = {
		val (w1, w2) = wirePair
		w1.isServer !=== w2.isServer
	}

}
