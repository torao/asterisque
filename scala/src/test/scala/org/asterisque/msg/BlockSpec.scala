/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.msg

import java.lang.reflect.Modifier

import org.specs2.Specification

import scala.util.Random

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// BlockSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class BlockSpec extends Specification { def is = s2"""
Block should:
declare as final. ${Modifier.isFinal(classOf[Block].getModifiers) must beTrue}
throw exception for illegal arguments in constructor. $e0
be eof of block of Block.eof(). ${(Block.eof(1).eof must beTrue) and (Block.eof(1).length == 0) and (Block.eof(1).loss === 0)}
return byte-array that contains same as constructor specified. $e1
return text as string. $e2
"""

	def e0 = {
		(new Block(1, null, 0, 0) must throwA[NullPointerException]) and
		(new Block(1, new Array[Byte](0), 1, 1) must throwA[IllegalArgumentException]) and
		(new Block(1, new Array[Byte](10), -1, 1) must throwA[IllegalArgumentException]) and
		(new Block(1, new Array[Byte](10), 1, -1) must throwA[IllegalArgumentException]) and
		(new Block(1, new Array[Byte](Block.MaxPayloadSize + 1), 0, Block.MaxPayloadSize + 1) must throwA[IllegalArgumentException])
	}

	def e1 = {
		val offset = 10
		val r = new Random()
		val b = new Array[Byte](256)
		r.nextBytes(b)
		val buffer1 = new Block(1, b, 0, b.length).toByteBuffer
		val buffer2 = new Block(1, b, offset, b.length - offset).toByteBuffer
		(b.length === buffer1.remaining()) and
			(0 until b.length).map{ i => b(i) === buffer1.get(i) }.reduce{ _ and _ } and
			(buffer2.position() === offset) and
			((b.length - offset) === buffer2.remaining()) and
			(0 until (b.length - offset)).map{ i => b(i + offset) === buffer2.get(buffer2.position() + i) }.reduce{ _ and _ }
	}

	def e2 = {
		val t = "あいうえお"
		val b = t.getBytes("UTF-8")
		val text = new Block(1, b, 0, b.length).getString
		text === t
	}

}
