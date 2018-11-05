/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.codec

import java.lang.{Boolean => JBoolean, Byte => JByte, Character => JChar, Double => JDouble, Float => JFloat, Integer => JInt, Long => JLong, Short => JShort}
import java.util
import java.util.{Collections, UUID}

import io.asterisque.Tuple
import org.specs2.Specification
import org.specs2.matcher.MatchResult

import scala.collection.JavaConversions
import scala.collection.JavaConversions._
import scala.util.Random
import scala.xml.dtd.ContentModel._labelT

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// TypeConversionSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class TypeConversionSpec extends Specification { def is = s2"""
TypeConversion should:
have default transfer-safe types. $e0
convert same value for transfer-safe types. $e01
not convert for default supported type. $e1
convert for default known type. $e2
convert parameter to call method. $e3
convert parameter implicitly for different type. $e4
transform null to zero-value for primitive types. $e5
convert Java transferable values to call method type. $e6
convert Scala transferable values to call method type. $e7
convert standard or Scala tuple and case class values. $e8
"""
  import TypeConversion._

  org.asterisque.init()

  def e0 = {
    Seq(
      null:Class[_],
      classOf[java.lang.Void],
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
      classOf[Tuple]
    ).map{ t => isDefaultSafeType(t) must beTrue }.reduce { _ and _ }
  }

  def e01 = {
    def ep(expected:Any, clazz:Class[_ <: Any]) = {
      TypeConversion.toMethodCall(TypeConversion.toTransfer(expected), clazz) === expected
    }
    def ea[T](expected:Array[T], clazz:Class[_ <: Any]) = {
      TypeConversion.toMethodCall(TypeConversion.toTransfer(expected), clazz) match {
        case actual:Array[T] =>
          actual.toList.zip(expected.toList).map{ case (a,b) => a === b }.fold(True){ _ and _ }
      }
    }
    val r = new Random()
    // Java Object
    Seq[MatchResult[_]](
      ep(null, classOf[Object]),
      ep(true, classOf[java.lang.Boolean]),
      ep(false, classOf[java.lang.Boolean]),
      ep(Byte.box(r.nextInt().toByte), classOf[java.lang.Byte]),
      ep(Short.box(r.nextInt().toShort), classOf[java.lang.Short]),
      ep(Int.box(r.nextInt()), classOf[Integer]),
      ep(Long.box(r.nextLong()), classOf[java.lang.Long]),
      ep(Float.box(r.nextFloat()), classOf[java.lang.Float]),
      ep(Double.box(r.nextDouble()), classOf[java.lang.Double]),
      ea((0 to 255).map{ _ => r.nextInt().toByte }.toArray, classOf[Array[Byte]]),
      ep(new String((0 to 255).map{ _ => ('A' + r.nextInt('Z' - 'A')).toChar }.toArray), classOf[String]),
      ep(UUID.randomUUID(), classOf[UUID]),
      {
        val expected = util.Arrays.asList(1, "A")
        TypeConversion.toMethodCall(TypeConversion.toTransfer(expected), classOf[util.List[_]]) match {
          case actual:java.util.List[_] =>
            actual.toList.zip(expected.toList).map{ case (a,b) => a === b }.fold(True){ _ and _ }
        }
      }, {
        val expected = new util.HashSet(util.Arrays.asList(1, "A"))
        TypeConversion.toMethodCall(TypeConversion.toTransfer(expected), classOf[util.Set[_]]) match {
          case actual:java.util.Set[_] =>
            (actual.toSet -- expected.toSet).size === 0
        }
      }, {
        val expected = JavaConversions.mapAsJavaMap(Map(1->2,"A"->"B"))
        TypeConversion.toMethodCall(TypeConversion.toTransfer(expected), classOf[util.Map[_,_]]) match {
          case actual:java.util.Map[_,_] =>
            actual.size === expected.size and
            actual.keys.map{ k => actual.get(k) === expected.get(k) }.fold(True){ _ and _ }
        }
      },
      // Scala Object
      // ep((), classOf[Unit]),     // classOf[Unit] == Void.TYPE のため () -> null -> null となり対称性がない
      ep(true, classOf[Boolean]),
      ep(false, classOf[Boolean]),
      ep(r.nextInt().toByte, classOf[Byte]),
      ep(r.nextInt().toShort, classOf[Short]),
      ep(r.nextInt(), classOf[Int]),
      ep(r.nextLong(), classOf[Long]),
      ep(r.nextFloat(), classOf[Float]),
      ep(r.nextDouble(), classOf[Double]),
      {
        val expected = List(1, "A")
        TypeConversion.toMethodCall(TypeConversion.toTransfer(expected), classOf[List[_]]) match {
          case actual:List[Any] =>
            actual.zip(expected).map{ case (a,b) => a === b }.fold(True){ _ and _ }
        }
      }, {
        val expected = Set("A", "B", "A", "C")
        TypeConversion.toMethodCall(TypeConversion.toTransfer(expected), classOf[Set[_]]) match {
          case actual:Set[String] =>
            actual.&~(expected).size === 0
        }
      }, {
        val expected = Map(1->2,"A"->"B")
        TypeConversion.toMethodCall(TypeConversion.toTransfer(expected), classOf[Map[_,_]]) match {
          case actual:Map[_,_] =>
            actual.size === expected.size and
              actual.keys.map{ k => actual.get(k) === expected.get(k) }.fold(True){ _ and _ }
        }
      }
    ).reduce { _ and _ }
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
      f(new Tuple {override def count():Int = 0
        override def schema():String = ""
        override def valueAt(i:Int):AnyRef = None
      }, classOf[Tuple])
  }

  def e2 = {
    def f(value:AnyRef, expected:Class[_]) = {
      expected.isAssignableFrom(TypeConversion.toTransfer(value).getClass) must beTrue
    }
    val obj = new Object()
    Seq(
    // Java Standard
      ts(Char.box('A'), "A"),
      tl(Array[AnyRef](obj, "hoge"), List(obj, "hoge")),
      tl(Array[Boolean](true, false), List(true, false)),
      tl(Array[Short](0.toShort, 1.toShort), List(0.toShort, 1.toShort)),
      tl(Array(0, 1), List(0, 1)),
      tl(Array(0.toLong, 1.toLong), List(0.toLong, 1.toLong)),
      tl(Array(0.toFloat, 1.toFloat), List(0.toFloat, 1.toFloat)),
      tl(Array(0.toDouble, 1.toDouble), List(0.toDouble, 1.toDouble)),
      ts("ABC".toCharArray, "ABC"),
      tl(new java.util.TreeSet[Int](){add(0);add(1)}, List(0, 1)),
    // Scala Extension
      tl(Seq(0, 1, 2), List(0, 1, 2)),
      tl(List(0, 1, 2), List(0, 1, 2)),
      tm(Map(0->1,"A"->"B"), Map(0->1,"A"->"B")),
      tp(true, java.lang.Boolean.TRUE),
      tp(0.toByte, java.lang.Byte.valueOf(0.toByte)),
      tp(0.toShort, java.lang.Short.valueOf(0.toShort)),
      tp(0, java.lang.Integer.valueOf(0)),
      tp(0.toLong, java.lang.Long.valueOf(0.toLong)),
      tp(0.toFloat, java.lang.Float.valueOf(0.toFloat)),
      tp(0.toDouble, java.lang.Double.valueOf(0.toDouble)),
      True
    ).reduce{_ and _}
  }

  def e3 = {
    mp("A", classOf[Character], 'A') and
      mp("", classOf[Character], '\0') and
      mp("ABC", classOf[Character], 'A') and
      mp("\u0378", classOf[Character], '\u0378') and    // Undefined Unicode
      ma("A", classOf[Array[Char]], List('A')) and
      ma("ABC", classOf[Array[Char]], List('A','B','C')) and
      ma("", classOf[Array[Char]], List()) and
    success
  }

  def e4 = {
    (TypeConversion.toMethodCall(true, classOf[JByte]) === 1.toByte) and
    success
  }

  def tp[T](value:Any, expected:T) = {
    TypeConversion.toTransfer(value) match {
      case actual:T => actual === expected
    }
  }

  def mp[T](value:Any, clazz:Class[_ <: Any], expected:T) = {
    TypeConversion.toMethodCall(value, clazz) match {
      case actual:T => actual === expected
    }
  }

  def ts(value:AnyRef, expected:String) = {
    TypeConversion.toTransfer(value) match {
      case actual:String => actual === expected
    }
  }

  def ma[T](value:Any, clazz:Class[_ <: Any], expected:List[T]) = {
    TypeConversion.toMethodCall(value, clazz) match {
      case actual:Array[T] =>
        actual.toList.zip(expected).map{ case (a,b) => a === b }.fold(True){ _ and _ }
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

  def e5 = Seq(
    TypeConversion.toMethodCall(null, classOf[Object]) === null,
    TypeConversion.toMethodCall(null, java.lang.Void.TYPE) === null,
    TypeConversion.toMethodCall(null, JBoolean.TYPE) === false,
    TypeConversion.toMethodCall(null, JByte.TYPE) === 0.toByte,
    TypeConversion.toMethodCall(null, JShort.TYPE) === 0.toShort,
    TypeConversion.toMethodCall(null, JInt.TYPE) === 0,
    TypeConversion.toMethodCall(null, JLong.TYPE) === 0.toLong,
    TypeConversion.toMethodCall(null, JFloat.TYPE) === 0.toFloat,
    TypeConversion.toMethodCall(null, JDouble.TYPE) === 0.toDouble,
    TypeConversion.toMethodCall(null, JChar.TYPE) === '\0',
    True
  ).reduce(_ and _)

  def e6 = Seq(
    TypeConversion.toMethodCall(null, classOf[Void]) === null,
    // boolean の数値評価は C と同じ
    TypeConversion.toMethodCall(JBoolean.TRUE, classOf[JByte]) !== 0,
    TypeConversion.toMethodCall(JBoolean.FALSE, classOf[JByte]) === 0,
    TypeConversion.toMethodCall(JBoolean.TRUE, classOf[JShort]) !== 0,
    TypeConversion.toMethodCall(JBoolean.FALSE, classOf[JShort]) === 0,
    TypeConversion.toMethodCall(JBoolean.TRUE, classOf[JInt]) !== 0,
    TypeConversion.toMethodCall(JBoolean.FALSE, classOf[JInt]) === 0,
    TypeConversion.toMethodCall(JBoolean.TRUE, classOf[JLong]) !== 0,
    TypeConversion.toMethodCall(JBoolean.FALSE, classOf[JLong]) === 0,
    TypeConversion.toMethodCall(JBoolean.TRUE, classOf[JFloat]) !== 0,
    TypeConversion.toMethodCall(JBoolean.FALSE, classOf[JFloat]) === 0,
    TypeConversion.toMethodCall(JBoolean.TRUE, classOf[JDouble]) !== 0,
    TypeConversion.toMethodCall(JBoolean.FALSE, classOf[JDouble]) === 0,
    TypeConversion.toMethodCall(JBoolean.TRUE, classOf[String]) === "true",
    TypeConversion.toMethodCall(JBoolean.FALSE, classOf[String]) === "false",
    // byte
    TypeConversion.toMethodCall(0.toByte, classOf[JBoolean]) === false,
    TypeConversion.toMethodCall(1.toByte, classOf[JBoolean]) === true,
    TypeConversion.toMethodCall(Byte.MaxValue, classOf[JShort]) === Byte.MaxValue.toShort,
    TypeConversion.toMethodCall(Byte.MinValue, classOf[JShort]) === Byte.MinValue.toShort,
    TypeConversion.toMethodCall(Byte.MaxValue, classOf[JInt]) === Byte.MaxValue.toInt,
    TypeConversion.toMethodCall(Byte.MinValue, classOf[JInt]) === Byte.MinValue.toInt,
    TypeConversion.toMethodCall(Byte.MaxValue, classOf[JLong]) === Byte.MaxValue.toLong,
    TypeConversion.toMethodCall(Byte.MinValue, classOf[JLong]) === Byte.MinValue.toLong,
    TypeConversion.toMethodCall(Byte.MaxValue, classOf[JFloat]) === Byte.MaxValue.toFloat,
    TypeConversion.toMethodCall(Byte.MinValue, classOf[JFloat]) === Byte.MinValue.toFloat,
    TypeConversion.toMethodCall(Byte.MaxValue, classOf[JDouble]) === Byte.MaxValue.toDouble,
    TypeConversion.toMethodCall(Byte.MinValue, classOf[JDouble]) === Byte.MinValue.toDouble,
    TypeConversion.toMethodCall(Byte.MaxValue, classOf[String]) === Byte.MaxValue.toString,
    TypeConversion.toMethodCall(Byte.MinValue, classOf[String]) === Byte.MinValue.toString,
    // short
    TypeConversion.toMethodCall(0.toShort, classOf[JBoolean]) === false,
    TypeConversion.toMethodCall(1.toShort, classOf[JBoolean]) === true,
    TypeConversion.toMethodCall(Short.MaxValue, classOf[JShort]) === Short.MaxValue.toShort,
    TypeConversion.toMethodCall(Short.MinValue, classOf[JShort]) === Short.MinValue.toShort,
    TypeConversion.toMethodCall(Short.MaxValue, classOf[JInt]) === Short.MaxValue.toInt,
    TypeConversion.toMethodCall(Short.MinValue, classOf[JInt]) === Short.MinValue.toInt,
    TypeConversion.toMethodCall(Short.MaxValue, classOf[JLong]) === Short.MaxValue.toLong,
    TypeConversion.toMethodCall(Short.MinValue, classOf[JLong]) === Short.MinValue.toLong,
    TypeConversion.toMethodCall(Short.MaxValue, classOf[JFloat]) === Short.MaxValue.toFloat,
    TypeConversion.toMethodCall(Short.MinValue, classOf[JFloat]) === Short.MinValue.toFloat,
    TypeConversion.toMethodCall(Short.MaxValue, classOf[JDouble]) === Short.MaxValue.toDouble,
    TypeConversion.toMethodCall(Short.MinValue, classOf[JDouble]) === Short.MinValue.toDouble,
    TypeConversion.toMethodCall(Short.MaxValue, classOf[String]) === Short.MaxValue.toString,
    TypeConversion.toMethodCall(Short.MinValue, classOf[String]) === Short.MinValue.toString,
    // int
    TypeConversion.toMethodCall(0.toInt, classOf[JBoolean]) === false,
    TypeConversion.toMethodCall(1.toInt, classOf[JBoolean]) === true,
    TypeConversion.toMethodCall(Int.MaxValue, classOf[JShort]) === Int.MaxValue.toShort,
    TypeConversion.toMethodCall(Int.MinValue, classOf[JShort]) === Int.MinValue.toShort,
    TypeConversion.toMethodCall(Int.MaxValue, classOf[JInt]) === Int.MaxValue.toInt,
    TypeConversion.toMethodCall(Int.MinValue, classOf[JInt]) === Int.MinValue.toInt,
    TypeConversion.toMethodCall(Int.MaxValue, classOf[JLong]) === Int.MaxValue.toLong,
    TypeConversion.toMethodCall(Int.MinValue, classOf[JLong]) === Int.MinValue.toLong,
    TypeConversion.toMethodCall(Int.MaxValue, classOf[JFloat]) === Int.MaxValue.toFloat,
    TypeConversion.toMethodCall(Int.MinValue, classOf[JFloat]) === Int.MinValue.toFloat,
    TypeConversion.toMethodCall(Int.MaxValue, classOf[JDouble]) === Int.MaxValue.toDouble,
    TypeConversion.toMethodCall(Int.MinValue, classOf[JDouble]) === Int.MinValue.toDouble,
    TypeConversion.toMethodCall(Int.MaxValue, classOf[String]) === Int.MaxValue.toString,
    TypeConversion.toMethodCall(Int.MinValue, classOf[String]) === Int.MinValue.toString,
    // long
    TypeConversion.toMethodCall(0.toLong, classOf[JBoolean]) === false,
    TypeConversion.toMethodCall(1.toLong, classOf[JBoolean]) === true,
    TypeConversion.toMethodCall(Long.MaxValue, classOf[JShort]) === Long.MaxValue.toShort,
    TypeConversion.toMethodCall(Long.MinValue, classOf[JShort]) === Long.MinValue.toShort,
    TypeConversion.toMethodCall(Long.MaxValue, classOf[JInt]) === Long.MaxValue.toInt,
    TypeConversion.toMethodCall(Long.MinValue, classOf[JInt]) === Long.MinValue.toInt,
    TypeConversion.toMethodCall(Long.MaxValue, classOf[JLong]) === Long.MaxValue.toLong,
    TypeConversion.toMethodCall(Long.MinValue, classOf[JLong]) === Long.MinValue.toLong,
    TypeConversion.toMethodCall(Long.MaxValue, classOf[JFloat]) === Long.MaxValue.toFloat,
    TypeConversion.toMethodCall(Long.MinValue, classOf[JFloat]) === Long.MinValue.toFloat,
    TypeConversion.toMethodCall(Long.MaxValue, classOf[JDouble]) === Long.MaxValue.toDouble,
    TypeConversion.toMethodCall(Long.MinValue, classOf[JDouble]) === Long.MinValue.toDouble,
    TypeConversion.toMethodCall(Long.MaxValue, classOf[String]) === Long.MaxValue.toString,
    TypeConversion.toMethodCall(Long.MinValue, classOf[String]) === Long.MinValue.toString,
    // float
    // TODO NaN, Infinite のテスト
    TypeConversion.toMethodCall(0.toFloat, classOf[JBoolean]) === false,
    TypeConversion.toMethodCall(1.toFloat, classOf[JBoolean]) === true,
    TypeConversion.toMethodCall(Float.MaxValue, classOf[JShort]) === Float.MaxValue.toShort,
    TypeConversion.toMethodCall(Float.MinValue, classOf[JShort]) === Float.MinValue.toShort,
    TypeConversion.toMethodCall(Float.MaxValue, classOf[JInt]) === Float.MaxValue.toInt,
    TypeConversion.toMethodCall(Float.MinValue, classOf[JInt]) === Float.MinValue.toInt,
    TypeConversion.toMethodCall(Float.MaxValue, classOf[JLong]) === Float.MaxValue.toLong,
    TypeConversion.toMethodCall(Float.MinValue, classOf[JLong]) === Float.MinValue.toLong,
    TypeConversion.toMethodCall(Float.MaxValue, classOf[JFloat]) === Float.MaxValue.toFloat,
    TypeConversion.toMethodCall(Float.MinValue, classOf[JFloat]) === Float.MinValue.toFloat,
    TypeConversion.toMethodCall(Float.MaxValue, classOf[JDouble]) === Float.MaxValue.toDouble,
    TypeConversion.toMethodCall(Float.MinValue, classOf[JDouble]) === Float.MinValue.toDouble,
    TypeConversion.toMethodCall(Float.MaxValue, classOf[String]) === Float.MaxValue.toString,
    TypeConversion.toMethodCall(Float.MinValue, classOf[String]) === Float.MinValue.toString,
    // double
    // TODO NaN, Infinite のテスト
    TypeConversion.toMethodCall(0.toDouble, classOf[JBoolean]) === false,
    TypeConversion.toMethodCall(1.toDouble, classOf[JBoolean]) === true,
    TypeConversion.toMethodCall(Double.MaxValue, classOf[JShort]) === Double.MaxValue.toShort,
    TypeConversion.toMethodCall(Double.MinValue, classOf[JShort]) === Double.MinValue.toShort,
    TypeConversion.toMethodCall(Double.MaxValue, classOf[JInt]) === Double.MaxValue.toInt,
    TypeConversion.toMethodCall(Double.MinValue, classOf[JInt]) === Double.MinValue.toInt,
    TypeConversion.toMethodCall(Double.MaxValue, classOf[JLong]) === Double.MaxValue.toLong,
    TypeConversion.toMethodCall(Double.MinValue, classOf[JLong]) === Double.MinValue.toLong,
    TypeConversion.toMethodCall(Double.NaN, classOf[JLong]) === Double.NaN.toLong,
    TypeConversion.toMethodCall(Double.PositiveInfinity, classOf[JLong]) === Double.PositiveInfinity.toLong,
    TypeConversion.toMethodCall(Double.NegativeInfinity, classOf[JLong]) === Double.NegativeInfinity.toLong,
    TypeConversion.toMethodCall(Double.MaxValue, classOf[JFloat]) === Double.MaxValue.toFloat,
    TypeConversion.toMethodCall(Double.MinValue, classOf[JFloat]) === Double.MinValue.toFloat,
    JFloat.isNaN(TypeConversion.toMethodCall(Double.NaN, classOf[JFloat])) must beTrue,
    JFloat.isInfinite(TypeConversion.toMethodCall(Double.PositiveInfinity, classOf[JFloat])) must beTrue,
    JFloat.isInfinite(TypeConversion.toMethodCall(Double.NegativeInfinity, classOf[JFloat])) must beTrue,
    TypeConversion.toMethodCall(Double.MaxValue, classOf[JDouble]) === Double.MaxValue.toDouble,
    TypeConversion.toMethodCall(Double.MinValue, classOf[JDouble]) === Double.MinValue.toDouble,
    JDouble.isNaN(TypeConversion.toMethodCall(Double.NaN, classOf[JDouble])) must beTrue,
    JDouble.isInfinite(TypeConversion.toMethodCall(Double.PositiveInfinity, classOf[JDouble])) must beTrue,
    JDouble.isInfinite(TypeConversion.toMethodCall(Double.NegativeInfinity, classOf[JDouble])) must beTrue,
    TypeConversion.toMethodCall(Double.MaxValue, classOf[String]) === Double.MaxValue.toString,
    TypeConversion.toMethodCall(Double.MinValue, classOf[String]) === Double.MinValue.toString,
    // String
    TypeConversion.toMethodCall("false", classOf[JBoolean]) === false,
    TypeConversion.toMethodCall("true", classOf[JBoolean]) === true,
    TypeConversion.toMethodCall(Short.MaxValue.toString, classOf[JShort]) === Short.MaxValue,
    TypeConversion.toMethodCall(Short.MinValue.toString, classOf[JShort]) === Short.MinValue,
    TypeConversion.toMethodCall(Int.MaxValue.toString, classOf[JInt]) === Int.MaxValue,
    TypeConversion.toMethodCall(Int.MinValue.toString, classOf[JInt]) === Int.MinValue,
    TypeConversion.toMethodCall(Long.MaxValue.toString, classOf[JLong]) === Long.MaxValue,
    TypeConversion.toMethodCall(Long.MinValue.toString, classOf[JLong]) === Long.MinValue,
    TypeConversion.toMethodCall(Float.MaxValue.toString, classOf[JFloat]) === Float.MaxValue,
    TypeConversion.toMethodCall(Float.MinValue.toString, classOf[JFloat]) === Float.MinValue,
    TypeConversion.toMethodCall(Double.MaxValue.toString, classOf[JDouble]) === Double.MaxValue,
    TypeConversion.toMethodCall(Double.MinValue.toString, classOf[JDouble]) === Double.MinValue,
    TypeConversion.toMethodCall(RandomUUID.toString, classOf[UUID]) === RandomUUID,
    // UUID
    TypeConversion.toMethodCall(RandomUUID, classOf[String]) === RandomUUID.toString,
    True
  ).reduce(_ and _)

  def e7 = Seq(
    TypeConversion.toMethodCall(null, classOf[Unit]) === (),
    // boolean の数値評価は C と同じ
    TypeConversion.toMethodCall(true, classOf[Byte]) !== 0,
    TypeConversion.toMethodCall(false, classOf[Byte]) === 0,
    TypeConversion.toMethodCall(true, classOf[Short]) !== 0,
    TypeConversion.toMethodCall(false, classOf[Short]) === 0,
    TypeConversion.toMethodCall(true, classOf[Int]) !== 0,
    TypeConversion.toMethodCall(false, classOf[Int]) === 0,
    TypeConversion.toMethodCall(true, classOf[Long]) !== 0,
    TypeConversion.toMethodCall(false, classOf[Long]) === 0,
    TypeConversion.toMethodCall(true, classOf[Float]) !== 0,
    TypeConversion.toMethodCall(false, classOf[Float]) === 0,
    TypeConversion.toMethodCall(true, classOf[Double]) !== 0,
    TypeConversion.toMethodCall(false, classOf[Double]) === 0,
    // byte
    TypeConversion.toMethodCall(0.toByte, classOf[Boolean]) === false,
    TypeConversion.toMethodCall(1.toByte, classOf[Boolean]) === true,
    TypeConversion.toMethodCall(Byte.MaxValue, classOf[Short]) === Byte.MaxValue.toShort,
    TypeConversion.toMethodCall(Byte.MinValue, classOf[Short]) === Byte.MinValue.toShort,
    TypeConversion.toMethodCall(Byte.MaxValue, classOf[Int]) === Byte.MaxValue.toInt,
    TypeConversion.toMethodCall(Byte.MinValue, classOf[Int]) === Byte.MinValue.toInt,
    TypeConversion.toMethodCall(Byte.MaxValue, classOf[Long]) === Byte.MaxValue.toLong,
    TypeConversion.toMethodCall(Byte.MinValue, classOf[Long]) === Byte.MinValue.toLong,
    TypeConversion.toMethodCall(Byte.MaxValue, classOf[Float]) === Byte.MaxValue.toFloat,
    TypeConversion.toMethodCall(Byte.MinValue, classOf[Float]) === Byte.MinValue.toFloat,
    TypeConversion.toMethodCall(Byte.MaxValue, classOf[Double]) === Byte.MaxValue.toDouble,
    TypeConversion.toMethodCall(Byte.MinValue, classOf[Double]) === Byte.MinValue.toDouble,
    // short
    TypeConversion.toMethodCall(0.toShort, classOf[Boolean]) === false,
    TypeConversion.toMethodCall(1.toShort, classOf[Boolean]) === true,
    TypeConversion.toMethodCall(Short.MaxValue, classOf[Short]) === Short.MaxValue.toShort,
    TypeConversion.toMethodCall(Short.MinValue, classOf[Short]) === Short.MinValue.toShort,
    TypeConversion.toMethodCall(Short.MaxValue, classOf[Int]) === Short.MaxValue.toInt,
    TypeConversion.toMethodCall(Short.MinValue, classOf[Int]) === Short.MinValue.toInt,
    TypeConversion.toMethodCall(Short.MaxValue, classOf[Long]) === Short.MaxValue.toLong,
    TypeConversion.toMethodCall(Short.MinValue, classOf[Long]) === Short.MinValue.toLong,
    TypeConversion.toMethodCall(Short.MaxValue, classOf[Float]) === Short.MaxValue.toFloat,
    TypeConversion.toMethodCall(Short.MinValue, classOf[Float]) === Short.MinValue.toFloat,
    TypeConversion.toMethodCall(Short.MaxValue, classOf[Double]) === Short.MaxValue.toDouble,
    TypeConversion.toMethodCall(Short.MinValue, classOf[Double]) === Short.MinValue.toDouble,
    // int
    TypeConversion.toMethodCall(0.toInt, classOf[Boolean]) === false,
    TypeConversion.toMethodCall(1.toInt, classOf[Boolean]) === true,
    TypeConversion.toMethodCall(Int.MaxValue, classOf[Short]) === Int.MaxValue.toShort,
    TypeConversion.toMethodCall(Int.MinValue, classOf[Short]) === Int.MinValue.toShort,
    TypeConversion.toMethodCall(Int.MaxValue, classOf[Int]) === Int.MaxValue.toInt,
    TypeConversion.toMethodCall(Int.MinValue, classOf[Int]) === Int.MinValue.toInt,
    TypeConversion.toMethodCall(Int.MaxValue, classOf[Long]) === Int.MaxValue.toLong,
    TypeConversion.toMethodCall(Int.MinValue, classOf[Long]) === Int.MinValue.toLong,
    TypeConversion.toMethodCall(Int.MaxValue, classOf[Float]) === Int.MaxValue.toFloat,
    TypeConversion.toMethodCall(Int.MinValue, classOf[Float]) === Int.MinValue.toFloat,
    TypeConversion.toMethodCall(Int.MaxValue, classOf[Double]) === Int.MaxValue.toDouble,
    TypeConversion.toMethodCall(Int.MinValue, classOf[Double]) === Int.MinValue.toDouble,
    // long
    TypeConversion.toMethodCall(0.toLong, classOf[Boolean]) === false,
    TypeConversion.toMethodCall(1.toLong, classOf[Boolean]) === true,
    TypeConversion.toMethodCall(Long.MaxValue, classOf[Short]) === Long.MaxValue.toShort,
    TypeConversion.toMethodCall(Long.MinValue, classOf[Short]) === Long.MinValue.toShort,
    TypeConversion.toMethodCall(Long.MaxValue, classOf[Int]) === Long.MaxValue.toInt,
    TypeConversion.toMethodCall(Long.MinValue, classOf[Int]) === Long.MinValue.toInt,
    TypeConversion.toMethodCall(Long.MaxValue, classOf[Long]) === Long.MaxValue.toLong,
    TypeConversion.toMethodCall(Long.MinValue, classOf[Long]) === Long.MinValue.toLong,
    TypeConversion.toMethodCall(Long.MaxValue, classOf[Float]) === Long.MaxValue.toFloat,
    TypeConversion.toMethodCall(Long.MinValue, classOf[Float]) === Long.MinValue.toFloat,
    TypeConversion.toMethodCall(Long.MaxValue, classOf[Double]) === Long.MaxValue.toDouble,
    TypeConversion.toMethodCall(Long.MinValue, classOf[Double]) === Long.MinValue.toDouble,
    // float
    // TODO NaN, Infinite のテスト
    TypeConversion.toMethodCall(0.toFloat, classOf[Boolean]) === false,
    TypeConversion.toMethodCall(1.toFloat, classOf[Boolean]) === true,
    TypeConversion.toMethodCall(Float.MaxValue, classOf[Short]) === Float.MaxValue.toShort,
    TypeConversion.toMethodCall(Float.MinValue, classOf[Short]) === Float.MinValue.toShort,
    TypeConversion.toMethodCall(Float.MaxValue, classOf[Int]) === Float.MaxValue.toInt,
    TypeConversion.toMethodCall(Float.MinValue, classOf[Int]) === Float.MinValue.toInt,
    TypeConversion.toMethodCall(Float.MaxValue, classOf[Long]) === Float.MaxValue.toLong,
    TypeConversion.toMethodCall(Float.MinValue, classOf[Long]) === Float.MinValue.toLong,
    TypeConversion.toMethodCall(Float.MaxValue, classOf[Float]) === Float.MaxValue.toFloat,
    TypeConversion.toMethodCall(Float.MinValue, classOf[Float]) === Float.MinValue.toFloat,
    TypeConversion.toMethodCall(Float.MaxValue, classOf[Double]) === Float.MaxValue.toDouble,
    TypeConversion.toMethodCall(Float.MinValue, classOf[Double]) === Float.MinValue.toDouble,
    // double
    // TODO NaN, Infinite のテスト
    TypeConversion.toMethodCall(0.toDouble, classOf[Boolean]) === false,
    TypeConversion.toMethodCall(1.toDouble, classOf[Boolean]) === true,
    TypeConversion.toMethodCall(Double.MaxValue, classOf[Short]) === Double.MaxValue.toShort,
    TypeConversion.toMethodCall(Double.MinValue, classOf[Short]) === Double.MinValue.toShort,
    TypeConversion.toMethodCall(Double.MaxValue, classOf[Int]) === Double.MaxValue.toInt,
    TypeConversion.toMethodCall(Double.MinValue, classOf[Int]) === Double.MinValue.toInt,
    TypeConversion.toMethodCall(Double.MaxValue, classOf[Long]) === Double.MaxValue.toLong,
    TypeConversion.toMethodCall(Double.MinValue, classOf[Long]) === Double.MinValue.toLong,
    TypeConversion.toMethodCall(Double.NaN, classOf[Long]) === Double.NaN.toLong,
    TypeConversion.toMethodCall(Double.PositiveInfinity, classOf[Long]) === Double.PositiveInfinity.toLong,
    TypeConversion.toMethodCall(Double.NegativeInfinity, classOf[Long]) === Double.NegativeInfinity.toLong,
    TypeConversion.toMethodCall(Double.MaxValue, classOf[Float]) === Double.MaxValue.toFloat,
    TypeConversion.toMethodCall(Double.MinValue, classOf[Float]) === Double.MinValue.toFloat,
    TypeConversion.toMethodCall(Double.NaN, classOf[Float]).isNaN must beTrue,
    TypeConversion.toMethodCall(Double.PositiveInfinity, classOf[Float]) === Float.PositiveInfinity,
    TypeConversion.toMethodCall(Double.NegativeInfinity, classOf[Float]) === Float.NegativeInfinity,
    TypeConversion.toMethodCall(Double.MaxValue, classOf[Double]) === Double.MaxValue.toDouble,
    TypeConversion.toMethodCall(Double.MinValue, classOf[Double]) === Double.MinValue.toDouble,
    TypeConversion.toMethodCall(Double.NaN, classOf[Double]).isNaN must beTrue,
    TypeConversion.toMethodCall(Double.PositiveInfinity, classOf[Double]) === Double.PositiveInfinity,
    TypeConversion.toMethodCall(Double.NegativeInfinity, classOf[Double]) === Double.NegativeInfinity,
    // String
    TypeConversion.toMethodCall("false", classOf[Boolean]) === false,
    TypeConversion.toMethodCall("true", classOf[Boolean]) === true,
    TypeConversion.toMethodCall(Short.MaxValue.toString, classOf[Short]) === Short.MaxValue,
    TypeConversion.toMethodCall(Short.MinValue.toString, classOf[Short]) === Short.MinValue,
    TypeConversion.toMethodCall(Int.MaxValue.toString, classOf[Int]) === Int.MaxValue,
    TypeConversion.toMethodCall(Int.MinValue.toString, classOf[Int]) === Int.MinValue,
    TypeConversion.toMethodCall(Long.MaxValue.toString, classOf[Long]) === Long.MaxValue,
    TypeConversion.toMethodCall(Long.MinValue.toString, classOf[Long]) === Long.MinValue,
    TypeConversion.toMethodCall(Float.MaxValue.toString, classOf[Float]) === Float.MaxValue,
    TypeConversion.toMethodCall(Float.MinValue.toString, classOf[Float]) === Float.MinValue,
    TypeConversion.toMethodCall(Double.MaxValue.toString, classOf[Double]) === Double.MaxValue,
    TypeConversion.toMethodCall(Double.MinValue.toString, classOf[Double]) === Double.MinValue,
    // Map
    reversible(Map("A"->0, 10->"X")),
    // Seq
    reversible(Seq("A", 0, 10, "X")),
    // List
    reversible(List("A", 0, 10, "X")),
    // Set
    reversible(Set("A", 0, 10, "X")),
    // Tuple
    reversible(new T0()),
    reversible(new T1(300)),
    reversible(new T2(-999, "Z")),
    // case class and scala tuple
    reversible(CT0()),
    reversible(CT1(943)),
    reversible(CT2(953, "I/O")),
    reversible(()),
    reversible((999)),
    reversible((433, "ZZZ")),
    True
  ).reduce(_ and _)

  val RandomUUID = UUID.randomUUID()

  val True = true must beTrue

  def reversible[T](value:T, `type`:Class[_ <: T])(cmp:(T,T)=>MatchResult[_]):MatchResult[_] = {
    cmp(TypeConversion.toMethodCall(TypeConversion.toTransfer(value), `type`), value)
  }
  def reversible(value:Seq[Any]):MatchResult[_] = {
    reversible(value, classOf[Seq[Any]]){ (a:Seq[Any], b:Seq[Any]) =>
      (a.length === b.length) and a.zip(b).map{ t => t._1 === t._2 }.fold(True){ _ and _ }
    }
  }
  def reversible(value:List[Any]):MatchResult[_] = {
    reversible(value, classOf[List[Any]]){ (a:List[Any], b:List[Any]) =>
      (a.length === b.length) and a.zip(b).map{ t => t._1 === t._2 }.fold(True){ _ and _ }
    }
  }
  def reversible(value:Set[Any]):MatchResult[_] = {
    reversible(value, classOf[Set[Any]]){ (a:Set[Any], b:Set[Any]) => a.&~(b).isEmpty must beTrue }
  }
  def reversible(value:Map[Any,_]):MatchResult[_] = {
    reversible(value, classOf[Map[Any,_]]){ (a:Map[Any,_], b:Map[Any,_]) =>
      (a.keys.toSet.&~(b.keys.toSet).isEmpty must beTrue) and
        a.keys.map{ k:Any => a(k) === b(k) }.fold(True){ _ and _ }
    }
  }
  def reversible(value:Tuple):MatchResult[_] = {
    reversible(value, value.getClass){ (a:Tuple, b:Tuple) =>
      (a.count() === b.count()) and (a.schema() === b.schema()) and
        (0 until a.count()).map{ i => a.valueAt(i) === b.valueAt(i) }.fold(True){_ and _}
    }
  }
  def reversible(value:Product):MatchResult[_] = {
    reversible(value, value.getClass){ (a:Product, b:Product) => a === b }
  }

  def e8 = Seq[MatchResult[_]](
  {
    // パラメータなしのタプルへの変換
    val t = new Tuple {
      override def count():Int = 0
      override def schema():String = ""
      override def valueAt(i:Int):AnyRef = ???
    }
    TypeConversion.toMethodCall(t, classOf[T0]).isInstanceOf[T0] must beTrue
  }, {
    // パラメータ数の一致しないタプルへの変換
    val t = new Tuple {
      override def count():Int = 1
      override def schema():String = ""
      override def valueAt(i:Int):AnyRef = Int.box(i)
    }
    TypeConversion.toMethodCall(t, classOf[T0]) must throwA[CodecException]
  }, {
    // 型の一致しないタプルへの変換
    val t = new Tuple {
      override def count():Int = 1
      override def schema():String = ""
      override def valueAt(i:Int):AnyRef = "this is string"
    }
    TypeConversion.toMethodCall(t, classOf[T1]) must throwA[CodecException]
  }, {
    // パラメータ数と型の一致するタプルへの変換 1
    val t = new Tuple {
      override def count():Int = 1
      override def schema():String = ""
      override def valueAt(i:Int):AnyRef = Int.box(i)
    }
    val t1 = TypeConversion.toMethodCall(t, classOf[T1])
    (t1.isInstanceOf[T1] must beTrue) and (t1.field1 === t.valueAt(0))
  }, {
    // パラメータ数と型の一致するタプルへの変換 2
    val f0 = random.nextInt()
    val f1 = random.alphanumeric.take(10).mkString
    val t = new Tuple {
      override def count():Int = 2
      override def schema():String = ""
      override def valueAt(i:Int):AnyRef = if(i==0) Int.box(f0) else f1
    }
    val t2 = TypeConversion.toMethodCall(t, classOf[T2])
    (t2.isInstanceOf[T2] must beTrue) and (t2.field1 === f0) and (t2.field2 === f1)
  }, {
    // スキームが有効なクラス名ではなく復元型が Tuple の場合は何もしない
    val f0 = random.nextInt()
    val f1 = random.alphanumeric.take(10).mkString
    val t = new Tuple {
      override def count():Int = 2
      override def schema():String = ""
      override def valueAt(i:Int):AnyRef = if(i==0) Int.box(f0) else f1
    }
    val t1 = TypeConversion.toMethodCall(t, classOf[Tuple])
    t1 === t
  }, {
    // リモート指定のクラス (スキーム) がサブクラスの場合はそれで復元
    val f0 = random.nextInt()
    val f1 = random.alphanumeric.take(10).mkString
    val t = new Tuple {
      override def count():Int = 2
      override def schema():String = classOf[T2].getName
      override def valueAt(i:Int):AnyRef = if(i==0) Int.box(f0) else f1
    }
    val t1 = TypeConversion.toMethodCall(t, classOf[T1])
    (t1.isInstanceOf[T2] must beTrue) and (t1.field1 === f0) and (t1.asInstanceOf[T2].field2 === f1)
  }
  ).reduce{_ and _}

  val random = new Random()

}

class T0 extends Tuple {
  override def count():Int = 0
  override def schema():String = ""
  override def valueAt(i:Int):AnyRef = None
}

class T1(val field1:Int) extends Tuple {
  override def count():Int = 1
  override def schema():String = ""
  override def valueAt(i:Int):AnyRef = Int.box(field1)
}

class T2(field1:Int, val field2:String) extends T1(field1) {
  override def count():Int = 2
  override def schema():String = ""
  override def valueAt(i:Int):AnyRef = if(i == 0) Int.box(field1) else field2
}

case class CT0()
case class CT1(x:Int)
case class CT2(x:Int, y:String)