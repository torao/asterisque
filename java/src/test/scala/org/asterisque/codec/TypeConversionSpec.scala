/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.codec

import java.util.{Collections, UUID}

import org.specs2.Specification
import org.specs2.matcher.MatchResult

import scala.collection.JavaConversions._

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// TypeConversionSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class TypeConversionSpec extends Specification { def is = s2"""
TypeConversion should:
be all safe for primitive types. $e0
not convert for default supported type. $e1
convert for default known type. $e2
"""
	import TypeConversion._

	org.asterisque.init()

	def e0 = {
		Seq(
			null:Class[_],
			classOf[java.lang.Boolean],
			classOf[java.lang.Byte],
			classOf[java.lang.Short],
			classOf[java.lang.Integer],
			classOf[java.lang.Long],
			classOf[java.lang.Float],
			classOf[java.lang.Double],
			classOf[Array[Byte]],
			classOf[String],
			classOf[java.util.UUID],
			classOf[java.util.List[_]],
			classOf[java.util.Map[_,_]],
			classOf[Struct]
		).map{ t => isDefaultSafeType(t) must beTrue }.reduce { _ and _ }
	}

	def e1 = {
		def f(value:AnyRef, expected:Class[_]) = {
			expected.isAssignableFrom(TypeConversion.toTransfer(value).getClass) must beTrue
		}
		(TypeConversion.toTransfer(null) === null) and
			tp(Boolean.box(true), java.lang.Boolean.TRUE) and
			f(Byte.box(0), classOf[java.lang.Byte]) and
			f(Short.box(0), classOf[java.lang.Short]) and
			f(Int.box(0), classOf[java.lang.Integer]) and
			f(Long.box(0), classOf[java.lang.Long]) and
			f(Float.box(0), classOf[java.lang.Float]) and
			f(Double.box(0), classOf[java.lang.Double]) and
			f(Array[Byte](), classOf[Array[Byte]]) and
			f("", classOf[String]) and
			f(UUID.randomUUID(), classOf[UUID]) and
			f(Collections.emptyList(), classOf[java.util.List[_]]) and
			f(Collections.emptyMap(), classOf[java.util.Map[_,_]]) and
			f(new Struct {override def count():Int = 0
				override def schema():String = ""
				override def valueAt(i:Int):AnyRef = ???
			}, classOf[Struct])
	}

	def e2 = {
		def f(value:AnyRef, expected:Class[_]) = {
			expected.isAssignableFrom(TypeConversion.toTransfer(value).getClass) must beTrue
		}
		val obj = new Object()
		// Java Standard
			ts(Char.box('A'), "A") and
			tl(Array[AnyRef](obj, "hoge"), List(obj, "hoge")) and
			tl(Array[Boolean](true, false), List(true, false)) and
			tl(Array[Short](0.toShort, 1.toShort), List(0.toShort, 1.toShort)) and
			tl(Array(0, 1), List(0, 1)) and
			tl(Array(0.toLong, 1.toLong), List(0.toLong, 1.toLong)) and
			tl(Array(0.toFloat, 1.toFloat), List(0.toFloat, 1.toFloat)) and
			tl(Array(0.toDouble, 1.toDouble), List(0.toDouble, 1.toDouble)) and
			ts("ABC".toCharArray, "ABC") and
			tl(new java.util.TreeSet[Int](){add(0);add(1)}, List(0, 1)) and
		// Scala Extension
			tl(Seq(0, 1, 2), List(0, 1, 2)) and
			tl(List(0, 1, 2), List(0, 1, 2)) and
			tm(Map(0->1,"A"->"B"), Map(0->1,"A"->"B")) and
			tp(true, java.lang.Boolean.TRUE) and
			tp(0.toByte, java.lang.Byte.valueOf(0.toByte)) and
			tp(0.toShort, java.lang.Short.valueOf(0.toShort)) and
			tp(0, java.lang.Integer.valueOf(0)) and
			tp(0.toLong, java.lang.Long.valueOf(0.toLong)) and
			tp(0.toFloat, java.lang.Float.valueOf(0.toFloat)) and
			tp(0.toDouble, java.lang.Double.valueOf(0.toDouble)) and
			tl((), List()) and    // Unit
		success
	}

	def tp[T](value:Any, expected:T) = {
		TypeConversion.toTransfer(value) match {
			case actual:T => actual === expected
		}
	}

	def ts(value:AnyRef, expected:String) = {
		TypeConversion.toTransfer(value) match {
			case actual:String => actual === expected
		}
	}

	def tl(value:Any, expected:List[_]) = {
		TypeConversion.toTransfer(value) match {
			case actual:java.util.List[_] =>
				actual.zip(expected).map{ case (a,b) => a === b }.fold(True){ _ and _ }
			case u =>
				System.err.println(s"unexpected list type: $u")
				true must beFalse
		}
	}

	def tm(value:AnyRef, expected:Map[Any,_]) = {
		TypeConversion.toTransfer(value) match {
			case actual:java.util.Map[_,_] =>
				(actual.keySet().toSet -- expected.keySet).size === 0 and
				expected.keySet.map{ k => actual.get(k) === expected(k) }.fold(True){ _ and _ }
		}
	}

	val True = true must beTrue

}
