package io.asterisque.core.codec

import java.util.UUID

import io.asterisque.core.Tuple
import io.asterisque.core.codec.MessageCodecSpec._
import io.asterisque.core.msg._
import org.specs2.Specification
import org.specs2.matcher.MatchResult

import scala.collection.JavaConverters._

/**
  * @author Takami Torao
  */
abstract class MessageCodecSpec extends Specification {
  def is =
    s2"""
${codec.getClass.getSimpleName} should:
  encode and decode messages with supported data-type. $encodeAndDecodeTransferableValues
"""

  def codec:MessageCodec

  private[this] def encodeAndDecodeTransferableValues:MatchResult[Any] = {
    Seq(
      supportedDataTypeSamples.zipWithIndex.map { case (value, i) =>
        new Open(1.toShort, i.toShort, Array[AnyRef](value))
      },
      supportedDataTypeSamples.map { value => new Close(2.toShort, value) },
      Seq(
        new Open(1.toShort, 0.toShort, supportedDataTypeSamples.toArray),
        new Close(1.toShort, supportedDataTypeSamples.asJava),
        Close.unexpectedError(103.toShort, ""),
        Close.unexpectedError(104.toShort, "foo"),
        new Block(1, Array[Byte](), 0, 0),
        Block.eof(1),
        new Block(1, Array[Byte](0, 1, 2, 3), 0, 4),
        new Block(2, (0 to 0xFF).map {
          _.toByte
        }.toArray, 5, 100)
      )
    ).flatten.map(msg => equals(msg, codec.decode(codec.encode(msg)).get)).reduceLeft(_ and _)
  }

  def equals(expected:Any, actual:Any):MatchResult[Any] = (expected, actual) match {
    case (null, null) => True
    case (expected:Open, actual:Open) =>
      (expected.params zip actual.params).map {
        case (p1, p2) => equals(p1, p2)
      }.reduceOption {
        _ and _
      }.getOrElse(True) and
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
          expected.payload.slice(expected.offset, expected.offset + expected.length),
          actual.payload.slice(actual.offset, actual.offset + actual.length)) must beTrue)
    case (i1:Seq[_], i2:Seq[_]) =>
      (i1 zip i2).map { case (p1, p2) => equals(p1, p2) }.foldLeft(True) {
        _ and _
      }
    case (i1:Map[Any, _], i2:Map[Any, _]) =>
      (i1.size === i2.size) and ((i1.keys.toList ++ i2.keys.toList).toSet.size === i1.size) and
        i1.keys.map { k => equals(i1(k), i2(k)) }.foldLeft(True) {
          _ and _
        }
    case (i1:Array[_], i2:Seq[_]) => equals(i1.toSeq, i2)
    case (i1:Seq[_], i2:Array[_]) => equals(i1, i2.toSeq)
    case (i1:Array[_], i2:Array[_]) => equals(i1.toSeq, i2.toSeq)
    case (i1:Abort, i2:Abort) => (i1.code === i2.code) and (i1.message === i2.message)
    case (i1:Array[_], i2:java.util.List[_]) => equals(i1.toSeq, i2.asScala)
    case (i1:Seq[_], i2:java.util.List[_]) => equals(i1, i2.asScala)
    case (i1:Map[_, _], i2:java.util.Map[_, _]) => equals(i1, i2.asScala.toMap)
    case (i1:Character, i2:String) => i1.toString === i2
    case (i1:Char, i2:String) => i1.toString === i2
    case (i1:Array[Char], i2:String) => new String(i1) === i2
    case ((), i2:java.util.List[_]) => i2.size() === 0
    case (i1:Product, i2:Tuple) =>
      (i1.productArity === i2.count()) and
        (0 until i1.productArity).map { i => equals(i1.productElement(i), i2.valueAt(i)) }.fold(True) {
          _ and _
        }
    case _ => expected === actual
  }

  private[this] val True:MatchResult[Any] = true must beTrue

  private[this] case class Sample1(text:String, number:Int) extends Serializable

  private[this] case class Sample2(list:List[Any], map:Map[Any, Any], obj:Sample1) extends Serializable

}

object MessageCodecSpec {

  val supportedDataTypeSamples:Seq[Object] = Seq(
    null,
    java.lang.Boolean.TRUE,
    java.lang.Boolean.FALSE,
    java.lang.Byte.valueOf(0x7F.toByte),
    java.lang.Short.valueOf(0x7FFF.toShort),
    Integer.valueOf(0x7FFFFFFF),
    java.lang.Long.valueOf(0x7FFFFFFFFFFFFFFFL),
    java.lang.Float.valueOf(0.1.toFloat),
    java.lang.Double.valueOf(0.01),
    "",
    "ABC",
    "\u0000\uFFFF",
    java.util.Arrays.asList(0, 1, 2, 3, 4, 5),
    UUID.randomUUID(),
    Tuple.ofUnit(),
    Tuple.of("A", 100)
  )
}