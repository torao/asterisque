/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.codec

import java.util.UUID

import org.asterisque.msg._
import org.specs2.Specification
import org.specs2.matcher.MatchResult

import scala.collection.JavaConversions._

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

	org.asterisque.init()

	def codec:Codec

	def encode01 = {
		Seq[Message](
			new Open(1.toShort, 0.toShort, Array[AnyRef]()),
			new Open(1.toShort, 1.toShort, Array[AnyRef](null)),
			new Open(1.toShort, 2.toShort, Array[AnyRef](java.lang.Boolean.TRUE)),
			new Open(1.toShort, 3.toShort, Array[AnyRef](java.lang.Boolean.FALSE)),
			new Open(1.toShort, 4.toShort, Array[AnyRef](java.lang.Byte.valueOf(0x7F.toByte))),
			new Open(1.toShort, 5.toShort, Array[AnyRef](java.lang.Short.valueOf(0x7FFF.toShort))),
			new Open(1.toShort, 6.toShort, Array[AnyRef](Integer.valueOf(0x7FFFFFFF))),
			new Open(1.toShort, 7.toShort, Array[AnyRef](java.lang.Long.valueOf(0x7FFFFFFFFFFFFFFFl))),
			new Open(1.toShort, 8.toShort, Array[AnyRef](java.lang.Float.valueOf(0.1.toFloat))),
			new Open(1.toShort, 9.toShort, Array[AnyRef](java.lang.Double.valueOf(0.01))),
			new Open(1.toShort, 10.toShort, Array[AnyRef]("")),
			new Open(1.toShort, 11.toShort, Array[AnyRef]("ABC")),
			new Open(1.toShort, 12.toShort, Array[AnyRef](Array())),
			new Open(1.toShort, 13.toShort, Array[AnyRef](Array(true, false))),
			new Open(1.toShort, 14.toShort, Array[AnyRef](Array(1.toShort, 2.toShort, 3.toShort))),
			new Open(1.toShort, 14.toShort, Array[AnyRef](Array(1, 2, 3))),
			new Open(1.toShort, 14.toShort, Array[AnyRef](Array(1.toLong, 2.toLong, 3.toLong))),
			new Open(1.toShort, 14.toShort, Array[AnyRef](Array(1.toFloat, 2.toFloat))),
			new Open(1.toShort, 14.toShort, Array[AnyRef](Array(1.toDouble, 2.toDouble))),
			new Open(1.toShort, 14.toShort, Array[AnyRef](Array('A', 'B', 'C'))),
			new Open(1.toShort, 15.toShort, Array[AnyRef](Array(false, -1, "xyz"))),
			new Open(1.toShort, 16.toShort, Array[AnyRef](java.lang.Boolean.TRUE, java.lang.Boolean.FALSE, Integer.valueOf(100), "hoge")),
			new Open(1.toShort, 17.toShort, Array[AnyRef](Map("A"->100,true->200,300->'X'), Seq("A",'b',100,false), Array("A",'b',300,true))),

			new Close(2.toShort, null.asInstanceOf[Object]),
			new Close(2.toShort, -100),
			new Close(2.toShort, "hoge"),
			new Close(2.toShort, Array()),
			new Close(2.toShort, Array(true, true)),
			new Close(2.toShort, Array(1, 2, 3)),
			new Close(2.toShort, Array(false, -1, "xyz")),
			new Close(2.toShort, ()),
			new Close(2.toShort, new Abort(0, "")),
			new Close(2.toShort, new Abort(1, "AAAAAAAAAAAAAAAAA")),
			Close.unexpectedError(103.toShort, ""),
			Close.unexpectedError(104.toShort, "foo"),

			new Block(1, Array[Byte](), 0, 0),
			Block.eof(1),
			new Block(1, Array[Byte](0, 1, 2, 3), 0, 4),
			new Block(2, (0 to 0xFF).map{_.toByte}.toArray, 5, 100)
		).map{ msg => equals(msg, codec.decode(codec.encode(msg)).get) }.reduceLeft{ _ and _ }
	}

	def supportedDataTypes = {
		val types:Seq[AnyRef] = Seq(
			null.asInstanceOf[AnyRef],
			().asInstanceOf[AnyRef],
			java.lang.Boolean.TRUE,
			java.lang.Byte.valueOf(0.toByte),
			java.lang.Short.valueOf(1.toShort),
			Integer.valueOf(2),
			java.lang.Long.valueOf(3),
			java.lang.Float.valueOf(0.1f),
			java.lang.Double.valueOf(0.2),
			java.lang.Character.valueOf('a'),
			"hoge":String,
			Array[Byte](0, 1, 2, 3, 4, 5),
			UUID.randomUUID(),
			Sample2(List(1, 2, "a", "b"), Map("x"->"y", 0->1), Sample1("abc", 233))
		)
		(types.map{ value => new Open(1.toShort, 0.toShort, Array(value)) }.toList ::: types.map{ value => new Close(1.toShort, value) }.toList).map{ msg =>
			equals(msg, codec.decode(codec.encode(msg)).get)
		}.reduceLeft{ _ and _ }
	}

	def equals(expected:Any, actual:Any):MatchResult[Any] = (expected, actual) match {
		case (null, null) => True
		case (expected:Open, actual:Open) =>
			(expected.params zip actual.params).map {
				case (p1, p2) => equals(p1, p2)
			}.reduceOption{ _ and _ }.getOrElse(True) and
				(expected.priority === actual.priority) and
				(expected.pipeId === actual.pipeId) and
				(expected.functionId === actual.functionId)
		case (expected:Close, actual:Close) =>
			(expected.pipeId === actual.pipeId) and
				(if(expected.abort != null) equals(expected.abort, actual.abort) else equals(expected.result, actual.result))
		case (expected:Block, actual:Block) =>
			(expected.pipeId === actual.pipeId) and
				(expected.eof === actual.eof) and
				(expected.loss === actual.loss) and
			(java.util.Arrays.equals(
				expected.payload.drop(expected.offset).take(expected.length).toArray,
				actual.payload.drop(actual.offset).take(actual.length).toArray) must beTrue)
		case (i1:Seq[_], i2:Seq[_]) =>
			(i1 zip i2).map{ case (p1, p2) => equals(p1, p2) }.foldLeft(True){ _ and _ }
		case (i1:Map[Any,_], i2:Map[Any,_]) =>
			(i1.size === i2.size) and ((i1.keys.toList ++ i2.keys.toList).toSet.size === i1.size) and
				i1.keys.map{ k => equals(i1(k), i2(k)) }.foldLeft(True){ _ and _ }
		case (i1:Array[_], i2:Seq[_]) => equals(i1.toSeq, i2)
		case (i1:Seq[_], i2:Array[_]) => equals(i1, i2.toSeq)
		case (i1:Array[_], i2:Array[_]) => equals(i1.toSeq, i2.toSeq)
		case (i1:Abort, i2:Abort) => (i1.code === i2.code) and (i1.message === i2.message)
		case (i1:Array[_], i2:java.util.List[_]) => equals(i1.toSeq, i2.toSeq)
		case (i1:Seq[_], i2:java.util.List[_]) => equals(i1, i2.toSeq)
		case (i1:Map[_,_], i2:java.util.Map[_,_]) => equals(i1, i2.toMap)
		case (i1:Character, i2:String) => i1.toString === i2
		case (i1:Char, i2:String) => i1.toString === i2
		case (i1:Array[Char], i2:String) => new String(i1) === i2
		case ((), i2:java.util.List[_]) => i2.size() === 0
		case (i1:Product, i2:Struct) =>
			(i1.productArity === i2.count()) and
				(0 until i1.productArity).map{ i => equals(i1.productElement(i), i2.valueAt(i)) }.fold(True){ _ and _ }
		case _ => expected === actual
	}

	val unexpected = true must beFalse
	val True:MatchResult[Any] = true must beTrue
}

class JavaCodecSpec extends CodecSpec {
	val codec = new JavaCodec()
}

class SimpleCodecSpec extends CodecSpec {
	val codec = SimpleCodec.getInstance()
}

class MessagePackCodecSpec extends CodecSpec {
	val codec = MessagePackCodec.getInstance()
}

case class Sample1(text:String, number:Int) extends Serializable
case class Sample2(list:List[Any], map:Map[Any,Any], obj:Sample1) extends Serializable
