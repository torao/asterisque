package io.karillon.blockchain.util.encode

import java.util
import java.util.Random

import io.asterisque.blockchain.util.encode.Base58._
import org.specs2.Specification

class Base53Spec extends Specification {
  def is =
    s2"""
It can encode 256-base digits (binary) to Base58. $encodeBinaryToBase58
It can decode Base58 string to 256-base byte sequence. $decodeBase58ToBinary
It can encode 256-base digits (binary) to Base58 with checksum. $encodeBinaryToBase58WithCheck
    """

  private[this] def encodeBinaryToBase58 = (encode(Array.empty) === "") and
    (encode(Array(0)) === "") and
    (encode(Array(0, 0, 0)) === "") and
    (encode(Array(1)) === "2") and
    (encode(Array(57)) === "z") and
    (encode(Array(58)) === "21") and
    (encode(Array(1, 0)) === "5R") and
    (encode(Array(0, 1, 0)) === "5R") and
    (encode(Array(xFF, xFF, xFF)) === "2UzHL") and
    (encode(Array(0, 0, 0, xFF, xFF, xFF)) === "2UzHL")

  private[this] def decodeBase58ToBinary = eq(decode(""), Array.empty[Byte]) and
    eq(decode("1"), Array.empty) and
    eq(decode("11"), Array.empty) and
    eq(decode("2"), Array[Byte](1)) and
    eq(decode("12"), Array[Byte](1)) and
    eq(decode("z"), Array[Byte](57)) and
    eq(decode("21"), Array[Byte](58)) and
    eq(decode("121"), Array[Byte](58)) and
    eq(decode("5R"), Array[Byte](1, 0)) and
    eq(decode("15R"), Array[Byte](1, 0)) and
    eq(decode("15T"), Array[Byte](1, 2)) and
    eq(decode("112UzHL"), Array[Byte](xFF, xFF, xFF)) and
    (decode("$") must throwA[IllegalArgumentException].like { case ex => ex.getMessage must contain("$") })

  private[this] def encodeBinaryToBase58WithCheck = {
    val random = new Random(23789)
    Seq(
      Array.empty[Byte],
      Array[Byte](0),
      Array[Byte](0, 0, 0),
      Array[Byte](1),
      Array[Byte](57),
      Array[Byte](58),
      Array[Byte](1, 0),
      Array[Byte](0, 1, 0),
      Array[Byte](xFF, xFF, xFF),
      Array[Byte](0, 0, 0, xFF, xFF, xFF)
    ).map { binary =>
      val version = random.nextInt().toByte
      val (actualVersion, actualBinary) = decodeWithCheck(encodeWithCheck(version, binary))
      (actualVersion === version) and eq(actualBinary, binary)
    }.reduceLeft(_ and _)
  }

  private[this] def eq(actual:Array[Byte], expected:Array[Byte]) = {
    (util.Arrays.equals(expected, actual) must beTrue).setMessage(actual.toJSON + " != " + expected.toJSON)
  }

  private[this] val xFF = 0xFF.toByte

  private implicit class _ByteArray(arr:Array[Byte]) {
    def toJSON:String = arr.map(_ & 0xFF).mkString("[", ",", "]")
  }

}
