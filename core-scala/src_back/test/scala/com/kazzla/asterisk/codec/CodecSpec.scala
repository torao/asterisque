/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.codec

import org.specs2.Specification
import com.kazzla.asterisk._
import org.specs2.matcher.MatchResult
import com.kazzla.asterisk.Close
import com.kazzla.asterisk.Open
import java.util.UUID
import org.asterisque.codec.{JavaCodec, Codec}

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
			Open(0, 0, Seq[Any]()),
			Open(0, 1, Seq[Any](null)),
			Open(0, 2, Seq[Any](true)),
			Open(0, 3, Seq[Any](false)),
			Open(0, 4, Seq[Any](0x7F.toByte)),
			Open(0, 5, Seq[Any](0x7FFF.toShort)),
			Open(0, 6, Seq[Any](0x7FFFFFFF)),
			Open(0, 7, Seq[Any](0x7FFFFFFFFFFFFFFFl)),
			Open(0, 8, Seq[Any](0.1.toFloat)),
			Open(0, 9, Seq[Any](0.01)),
			Open(0, 10, Seq[Any]("")),
			Open(0, 11, Seq[Any]("ABC")),
			Open(0, 12, Seq[Any](Array())),
			Open(0, 13, Seq[Any](Array(true, false))),
			Open(0, 14, Seq[Any](Array(1, 2, 3))),
			Open(0, 15, Seq[Any](Array(false, -1, "xyz"))),
			Open(0, 16, Seq[Any](true, false, 100, "hoge")),
			Open(0, 17, Seq[Any](Map("A"->100,'A'->200,300->'X'), Seq("A",'b',100,false), Array("A",'b',300,true))),

			Close.success(0, null),
			Close.success(1, -100),
			Close.success(2, "hoge"),
			Close.success(3, Array()),
			Close.success(4, Array(true, true)),
			Close.success(5, Array(1, 2, 3)),
			Close.success(6, Array(false, -1, "xyz")),
			Close.success(7, ()),
			Close.error(101, 0, "", ""),
			Close.error(102, 1, "AAAAAAAAAAAAAAAAA", "BB"),
			Close.unexpectedError(103.toShort, ""),
			Close.unexpectedError(104.toShort, new java.lang.Throwable()),
			Close.unexpectedError(105.toShort, Abort(10, "msg", "desc")),

			Block(0, Array[Byte]()),
			Block.eof(0),
			Block(1, Array[Byte](0, 1, 2, 3)),
			Block(2, (0 to 0xFF).map{_.toByte}.toArray, 5, 100)
		).map{ msg => equals(msg, codec.decode(codec.encode(msg)).get) }.reduceLeft{ _ and _ }
	}

	def supportedDataTypes = {
		val types = Seq(
			null,
			(),
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
			UUID.randomUUID(),
			Sample2(List(1, 2, "a", "b"), Map("x"->"y", 0->1), Sample1("abc", 233))
		)
		(types.map{ value => Open(0, 0, Seq(value)) }.toList ::: types.map{ value => Close(0, Right(value)) }.toList).map{ msg =>
			equals(msg, codec.decode(codec.encode(msg)).get)
		}.reduceLeft{ _ and _ }
	}

	def equals(expected:Any, actual:Any):MatchResult[Any] = (expected, actual) match {
		case (expected:Open, actual:Open) =>
			(expected.params zip actual.params).map {
				case (p1, p2) => equals(p1, p2)
			}.foldLeft(True){ _ and _ } and (expected.pipeId === actual.pipeId) and (expected.function === actual.function)
		case (expected:Close, actual:Close) =>
			(expected.pipeId === actual.pipeId) and
				(expected.result.isRight === actual.result.isRight) and
				equals(
					expected.result.right.getOrElse(expected.result.left.get),
					actual.result.right.getOrElse(actual.result.left.get))
		case (expected:Block, actual:Block) =>
			(expected.pipeId === actual.pipeId) and
			(expected.eof === actual.eof) and
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
	val codec = new JavaCodec()
}

class MsgPackCodecSpec extends CodecSpec {
	val codec = MsgPackCodec
}

case class Sample1(text:String, number:Int) extends Serializable
case class Sample2(list:List[Any], map:Map[Any,Any], obj:Sample1) extends Serializable
