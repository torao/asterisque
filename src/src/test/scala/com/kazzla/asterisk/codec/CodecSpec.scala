/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.codec

import org.specs2.Specification
import com.kazzla.asterisk._
import org.specs2.matcher.MatchResult
import scala.collection.JavaConversions._
import com.kazzla.asterisk.Close
import com.kazzla.asterisk.Open
import java.util.UUID

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// CodecSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
abstract class CodecSpec extends Specification { def is = s2"""
${codec.getClass.getSimpleName} should:
encode and decode variable messages. $encode01
encode and decode supported data-types. $supportedDataTypes
"""

	def codec:Codec

	def encode01 = {
		Seq[Message](
			Open(0, 0),
			Open(0, 1, null),
			Open(0, 2, true),
			Open(0, 3, false),
			Open(0, 4, 0x7F.toByte),
			Open(0, 5, 0x7FFF.toShort),
			Open(0, 6, 0x7FFFFFFF),
			Open(0, 7, 0x7FFFFFFFFFFFFFFFl),
			Open(0, 8, 0.1.toFloat),
			Open(0, 9, 0.01),
			Open(0, 10, ""),
			Open(0, 11, "ABC"),
			Open(0, 12, Array()),
			Open(0, 13, Array(true, false)),
			Open(0, 14, Array(1, 2, 3)),
			Open(0, 15, Array(false, -1, "xyz")),
			Open(0, 16, true, false, 100, "hoge"),
			Open(0, 17, Map("A"->100,'A'->200,300->'X'), Seq("A",'b',100,false), Array("A",'b',300,true)),

			Close(0, null, null),
			Close(1, -100, null),
			Close(2, "hoge", null),
			Close(3, Array(), null),
			Close(4, Array(true, true), null),
			Close(5, Array(1, 2, 3), null),
			Close(6, Array(false, -1, "xyz"), null),
			Close(100, null, ""),
			Close(101, null, "AAAAAAAAAAAAAAAAA"),

			Block(0, Array[Byte]()),
			Block(1, Array[Byte](0, 1, 2, 3)),
			Block(2, (0 to 0xFF).map{_.toByte}.toArray, 5, 100)
		).map{ msg => equals(msg, codec.decode(codec.encode(msg)).get) }.reduceLeft{ _ and _ }
	}

	def supportedDataTypes = {
		val types = Seq(
			null,
			true:Boolean,
			0:Byte,
			1:Short,
			2:Int,
			3:Long,
			0.1f:Float,
			0.2:Double,
			'a':Char,
			"hoge":String,
			Array[Byte](0, 1, 2, 3, 4, 5),
			UUID.randomUUID()
		)
		(types.map{ value => Open(0, 0, value) }.toList ::: types.map{ value => Close(0, value, null) }.toList).map{ msg =>
			equals(msg, codec.decode(codec.encode(msg)).get)
		}.reduceLeft{ _ and _ }
	}

	def equals(expected:Any, actual:Any):MatchResult[Any] = (expected, actual) match {
		case (expected:Open, actual:Open) =>
			(expected.params zip actual.params).map {
				case (p1, p2) => equals(p1, p2)
			}.foldLeft(True){ _ and _ } and (expected.pipeId === actual.pipeId) and (expected.function === actual.function)
		case (expected:Close[_], actual:Close[_]) =>
			(expected.pipeId === actual.pipeId) and
				equals(expected.result, actual.result) and
				equals(expected.errorMessage, actual.errorMessage)
		case (expected:Block, actual:Block) =>
			(expected.pipeId === actual.pipeId) and
			(java.util.Arrays.equals(
				expected.payload.drop(expected.offset).take(expected.length).toArray,
				actual.payload.drop(actual.offset).take(actual.length).toArray) must beTrue)
		case (i1:Seq[_], i2:Seq[_]) =>
				(i1 zip i2).map{ case (p1, p2) => equals(p1, p2) }.foldLeft(True){ _ and _ }
		case (i1:Array[_], i2:Seq[_]) => equals(i1.toSeq, i2)
		case (i1:Seq[_], i2:Array[_]) => equals(i1, i2.toSeq)
		case (i1:Array[_], i2:Array[_]) => equals(i1.toSeq, i2.toSeq)
		case _ => expected === actual
	}

	val unexpected = true must beFalse
	val True:MatchResult[Any] = true must beTrue
}

class JavaCodecSpec extends CodecSpec {
	val codec = JavaCodec
}

class MsgPackCodecSpec extends CodecSpec {
	val codec = MsgPackCodec
}
