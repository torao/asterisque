package io.asterisque.wire.rpc

import java.nio.charset.StandardCharsets

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Try

class StandardTransformer extends Transformer {
  assert(classOf[String] == classOf[java.lang.String])
  assert(classOf[Boolean] == java.lang.Boolean.TYPE)
  assert(classOf[Int] == java.lang.Integer.TYPE)

  private[this] val AnyToBoolean:PartialFunction[Any, Boolean] = {
    case i:Byte => i != 0
    case i:Char => i != 0
    case i:Short => i != 0
    case i:Int => i != 0
    case i:Long => i != 0
    case i:Float => i != 0
    case i:Double => i != 0
    case b:java.lang.Boolean => if(b) true else false
    case i:java.lang.Number => i.intValue() != 0
    case s:String if Try(s.toBoolean).isSuccess => s.toBoolean
  }

  typedef(classOf[Boolean])(AnyToBoolean)
  typedef(classOf[java.lang.Boolean]) { case i => java.lang.Boolean.valueOf(AnyToBoolean(i)) }

  private[this] val AnyToLong:PartialFunction[Any, Long] = {
    case b:Boolean => (if(b) 1 else 0).toLong
    case i:Byte => i.toLong
    case i:Char => i.toLong
    case i:Short => i.toLong
    case i:Int => i.toLong
    case i:Float => i.toLong
    case i:Double => i.toLong
    case b:java.lang.Boolean => (if(b) 1 else 0).toLong
    case i:java.lang.Number => i.longValue()
    case s:String if Try(s.toLong).isSuccess => s.toLong
  }

  typedef(classOf[Byte]) { case i => AnyToLong(i).toByte }
  typedef(classOf[Short]) { case i => AnyToLong(i).toShort }
  typedef(classOf[Int]) { case i => AnyToLong(i).toInt }
  typedef(classOf[Long])(AnyToLong)

  typedef(classOf[java.lang.Byte]) { case i => java.lang.Byte.valueOf(AnyToLong(i).toByte) }
  typedef(classOf[java.lang.Short]) { case i => java.lang.Short.valueOf(AnyToLong(i).toByte) }
  typedef(classOf[java.lang.Integer]) { case i => java.lang.Integer.valueOf(AnyToLong(i).toInt) }
  typedef(classOf[java.lang.Long]) { case i => java.lang.Long.valueOf(AnyToLong(i)) }

  private[this] val AnyToDouble:PartialFunction[Any, Double] = {
    case b:Boolean => (if(b) 1 else 0).toDouble
    case i:Byte => i.toDouble
    case i:Char => i.toDouble
    case i:Short => i.toDouble
    case i:Int => i.toDouble
    case i:Long => i.toDouble
    case i:Float => i.toDouble
    case b:java.lang.Boolean => (if(b) 1 else 0).toDouble
    case i:java.lang.Number => i.doubleValue()
    case s:String if Try(s.toDouble).isSuccess => s.toDouble
  }

  typedef(classOf[Float]) { case i => AnyToDouble(i).toFloat }
  typedef(classOf[Double])(AnyToDouble)

  typedef(classOf[java.lang.Float]) { case i => java.lang.Float.valueOf(AnyToDouble(i).toFloat) }
  typedef(classOf[java.lang.Double]) { case i => java.lang.Double.valueOf(AnyToDouble(i)) }

  private[this] val AnyToChar:PartialFunction[Any, Char] = {
    case b:Boolean => if(b) '1' else '0'
    case i:Byte => i.toChar
    case i:Short => i.toChar
    case i:Int => i.toChar
    case i:Long => i.toChar
    case i:Float => i.toChar
    case i:Double => i.toChar
    case b:java.lang.Boolean => if(b) '1' else '0'
    case i:java.lang.Number => i.intValue().toChar
    case s:String if s.nonEmpty => s.head
  }

  typedef(classOf[Char])(AnyToChar)
  typedef(classOf[java.lang.Character]) { case i => java.lang.Character.valueOf(AnyToChar(i)) }

  typedef(classOf[String]) {
    case x@(_:Boolean | _:Byte | _:Char | _:Short | _:Int | _:Long | _:Float | _:Double | _:java.lang.Boolean | _:java.lang.Number) =>
      x.toString
    case a:Array[Char] => new String(a)
    case a:Array[Byte] => new String(a, StandardCharsets.UTF_8)
  }

  typedef(classOf[Array[Byte]]) {
    case s:String => s.getBytes(StandardCharsets.UTF_8)
  }

  private[this] val AnyToMap:PartialFunction[Any, Map[_, _]] = {
    case m:java.util.Map[_, _] => m.asScala.toMap
    case m:mutable.Map[_, _] => m.toMap
  }

  typedef(classOf[Map[_, _]])(AnyToMap)
  typedef(classOf[mutable.Map[_, _]]) { case x => mutable.Map(AnyToMap(x).toSeq:_*) }
  typedef(classOf[java.util.Map[_, _]]) { case x => AnyToMap(x).asJava }

  private[this] val AnyToList:PartialFunction[Any, List[_]] = {
    case i:TraversableOnce[_] => i.toList
    case i:java.lang.Iterable[_] => i.asScala.toList
    case i:Array[_] => i.toList
  }

  typedef(classOf[List[_]])(AnyToList)
  typedef(classOf[Seq[_]])(AnyToList)
  typedef(classOf[Iterable[_]])(AnyToList)
  typedef(classOf[Set[_]]) { case x => AnyToList(x).toSet }
  typedef(classOf[mutable.Buffer[_]]) { case x => mutable.Buffer(AnyToList(x):_*) }
  typedef(classOf[java.util.List[_]]) { case x => AnyToList(x).asJava }
  typedef(classOf[java.util.Collection[_]]) { case x => AnyToList(x).asJava }
  typedef(classOf[java.lang.Iterable[_]]) { case x => AnyToList(x).asJava }
  typedef(classOf[Array[_]]) { case x => AnyToList(x).toArray }

}
