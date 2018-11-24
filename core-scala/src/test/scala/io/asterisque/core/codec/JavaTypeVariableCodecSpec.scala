package io.asterisque.core.codec

import java.lang.{Boolean => JBoolean, Byte => JByte, Character => JChar, Double => JDouble, Float => JFloat, Integer => JInt, Long => JLong, Short => JShort, Void => JVoid}
import java.nio.charset.StandardCharsets
import java.util.UUID

import io.asterisque.core.Tuple
import org.specs2.Specification

import scala.collection.JavaConverters._

class JavaTypeVariableCodecSpec extends Specification {
  def is =
    s2"""
  can convert default transferable values as the same value. $defaultTransferable
  can convert primitive to wrapper. $primitiveToWrapper
  should convert null for primitive types. $nullToPrimitive
  should return null for void type. $voidConversion
  should convert between numeric types. $numericConversion
  should convert string. $stringConversion
  should convert array. $arrayConversion
"""

  private[this] val codec = new VariableCodec(new JavaTypeVariableCodec())

  private[this] def defaultTransferable = {
    MessageCodecSpec.supportedDataTypeSamples.map { value =>
      val obj = codec.nativeToTransferable(value)
      (VariableCodec.isTransferable(codec.nativeToTransferable(value)) must beTrue) and
        (if(value == null) {
          obj must beNull
        } else {
          obj === value
        })
    }.reduceLeft(_ and _)
  }

  private[this] def primitiveToWrapper = {
    Seq(
      (JVoid.TYPE, null),
      (JByte.TYPE, JByte.valueOf(0.toByte)),
      (JShort.TYPE, JShort.valueOf(0.toShort)),
      (JInt.TYPE, JInt.valueOf(0)),
      (JLong.TYPE, JLong.valueOf(0)),
      (JFloat.TYPE, JFloat.valueOf(0)),
      (JDouble.TYPE, JDouble.valueOf(0)),
      (JChar.TYPE, "A"),
      (JBoolean.TYPE, JBoolean.valueOf(true))
    ).map { case (ptype, value) =>
      val obj = codec.transferableToNative(value, ptype)
      obj === value
    }.reduceLeft(_ and _)
  }

  private[this] def nullToPrimitive = {
    Seq(
      (JVoid.TYPE, null),
      (JByte.TYPE, 0.toByte),
      (JShort.TYPE, 0.toShort),
      (JInt.TYPE, 0),
      (JLong.TYPE, 0.toLong),
      (JFloat.TYPE, 0.0f),
      (JDouble.TYPE, 0.0),
      (JChar.TYPE, '\u0000'),
      (JBoolean.TYPE, false)
    ).map { case (ptype, expected) =>
      val obj = codec.transferableToNative(null, ptype)
      obj === expected
    }.reduceLeft(_ and _)
  }

  private[this] def voidConversion = {
    Seq(
      null,
      true, false,
      0.toByte, Byte.MaxValue, Byte.MinValue,
      0.toShort, Short.MaxValue, Short.MinValue,
      0, Int.MaxValue, Int.MinValue,
      0.toLong, Long.MaxValue, Long.MinValue,
      0.0f, Float.MaxValue, Float.MinValue,
      0.0, Double.MaxValue, Double.MinValue,
      "", "ABC",
      "ABC".getBytes(StandardCharsets.UTF_8),
      UUID.randomUUID(),
      Map("A" -> "B", "x" -> "y").asJava,
      Seq("A", "B", "C").asJava,
      Tuple.of("A", 1, 100)
    ).map { value =>
      codec.transferableToNative(value, JVoid.TYPE) === null
    }.reduceLeft(_ and _)
  }

  private[this] def numericConversion = {
    Seq(
      (classOf[JBoolean], null, false),
      (classOf[JBoolean], Byte.MaxValue, true),
      (classOf[JBoolean], 0.toByte, false),
      (classOf[JBoolean], Short.MaxValue, true),
      (classOf[JBoolean], 0.toShort, false),
      (classOf[JBoolean], Int.MaxValue, true),
      (classOf[JBoolean], 0, false),
      (classOf[JBoolean], Long.MaxValue, true),
      (classOf[JBoolean], 0.toLong, false),
      (classOf[JBoolean], Float.MaxValue, true),
      (classOf[JBoolean], 0.toFloat, false),
      (classOf[JBoolean], Double.MaxValue, true),
      (classOf[JBoolean], 0.toDouble, false),
      (classOf[JBoolean], Double.NaN, false),
      (classOf[JBoolean], Double.PositiveInfinity, false),
      (classOf[JBoolean], Double.NegativeInfinity, false),
      (classOf[JBoolean], "true", true),
      (classOf[JBoolean], "false", false),
      (classOf[JBoolean], "", false),
      (classOf[JByte], null, 0.toByte),
      (classOf[JByte], false, 0.toByte),
      (classOf[JByte], true, 1.toByte),
      (classOf[JByte], Short.MaxValue, Short.MaxValue.toByte),
      (classOf[JByte], Int.MaxValue, Int.MaxValue.toByte),
      (classOf[JByte], Long.MaxValue, Long.MaxValue.toByte),
      (classOf[JByte], Float.MaxValue, Float.MaxValue.toByte),
      (classOf[JByte], Double.MaxValue, Double.MaxValue.toByte),
      (classOf[JByte], "1", 1.toByte),
      (classOf[JShort], null, 0.toShort),
      (classOf[JShort], false, 0.toShort),
      (classOf[JShort], true, 1.toShort),
      (classOf[JShort], Byte.MaxValue, Byte.MaxValue.toShort),
      (classOf[JShort], Int.MaxValue, Int.MaxValue.toShort),
      (classOf[JShort], Long.MaxValue, Long.MaxValue.toShort),
      (classOf[JShort], Float.MaxValue, Float.MaxValue.toShort),
      (classOf[JShort], Double.MaxValue, Double.MaxValue.toShort),
      (classOf[JShort], "1", 1.toShort),
      (classOf[JInt], null, 0),
      (classOf[JInt], false, 0),
      (classOf[JInt], true, 1),
      (classOf[JInt], Byte.MaxValue, Byte.MaxValue.toInt),
      (classOf[JInt], Short.MaxValue, Short.MaxValue.toInt),
      (classOf[JInt], Long.MaxValue, Long.MaxValue.toInt),
      (classOf[JInt], Float.MaxValue, Float.MaxValue.toInt),
      (classOf[JInt], Double.MaxValue, Double.MaxValue.toInt),
      (classOf[JInt], "1", 1.toShort),
      (classOf[JLong], null, 0.toLong),
      (classOf[JLong], false, 0.toLong),
      (classOf[JLong], true, 1.toLong),
      (classOf[JLong], Byte.MaxValue, Byte.MaxValue.toLong),
      (classOf[JLong], Short.MaxValue, Short.MaxValue.toLong),
      (classOf[JLong], Int.MaxValue, Int.MaxValue.toLong),
      (classOf[JLong], Float.MaxValue, Float.MaxValue.toLong),
      (classOf[JLong], Double.MaxValue, Double.MaxValue.toLong),
      (classOf[JLong], "1", 1.toLong),
      (classOf[JFloat], null, 0.0f),
      (classOf[JFloat], false, 0.0f),
      (classOf[JFloat], true, 1.0f),
      (classOf[JFloat], Byte.MaxValue, Byte.MaxValue.toFloat),
      (classOf[JFloat], Short.MaxValue, Short.MaxValue.toFloat),
      (classOf[JFloat], Int.MaxValue, Int.MaxValue.toFloat),
      (classOf[JFloat], Long.MaxValue, Long.MaxValue.toFloat),
      (classOf[JFloat], Double.MaxValue, Double.MaxValue.toFloat),
      (classOf[JFloat], "1.0", 1.0f),
      (classOf[JDouble], null, 0.0),
      (classOf[JDouble], false, 0.0),
      (classOf[JDouble], true, 1.0),
      (classOf[JDouble], Byte.MaxValue, Byte.MaxValue.toDouble),
      (classOf[JDouble], Short.MaxValue, Short.MaxValue.toDouble),
      (classOf[JDouble], Int.MaxValue, Int.MaxValue.toDouble),
      (classOf[JDouble], Long.MaxValue, Long.MaxValue.toDouble),
      (classOf[JDouble], Float.MaxValue, Float.MaxValue.toDouble),
      (classOf[JDouble], "1.0", 1.0)
    ).map { case (ptype, value, expected) =>
      codec.transferableToNative(codec.nativeToTransferable(value), ptype) === expected
    }.reduceLeft(_ and _)
  }

  private[this] def stringConversion = {
    (Seq(
      (null, null),
      (Array[Byte]('A', 'B', 'C'), "ABC")
    ) ++ Seq(
      true, false,
      0.toByte, Byte.MaxValue, Byte.MinValue,
      0.toShort, Short.MaxValue, Short.MinValue,
      0.toByte, Int.MaxValue, Int.MinValue,
      0.toByte, Long.MaxValue, Long.MinValue,
      0.toByte, Float.MaxValue, Float.MinValue, Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity,
      0.toByte, Double.MaxValue, Double.MinValue, Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity,
      UUID.randomUUID()
    ).map(value => (value, String.valueOf(value)))).map { case (value, expected) =>
      codec.transferableToNative(value, classOf[String]) === expected
    }.reduceLeft(_ and _)
  }

  private[this] def arrayConversion = {
    Seq(
      Array[Boolean](true, true, false),
      Array[Short](0.toShort, Short.MaxValue, Short.MinValue),
      Array[Int](0, Int.MaxValue, Int.MinValue),
      Array[Long](0.toLong, Long.MaxValue, Long.MinValue),
      Array[Float](0.0f, Float.MaxValue, Float.MinValue),
      Array[Double](0.0, Double.MaxValue, Double.MinValue),
      Array[Char]('A', 'B', 'C'),
      Array[String]("A", "B", "C")
    ).map { value =>
      val list = codec.nativeToTransferable(value)
      codec.transferableToNative(list, value.getClass) === value
    }.reduceLeft(_ and _)
  }

}
