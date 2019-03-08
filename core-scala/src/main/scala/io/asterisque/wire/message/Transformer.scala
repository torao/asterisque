package io.asterisque.wire.message

import java.lang.reflect.{Constructor, Method}
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

import io.asterisque.utils.Debug
import javax.annotation.{Nonnull, Nullable}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Try

trait Transformer {

  private[this] val typeMap = mutable.HashMap[Class[_], PartialFunction[Any, _]]()

  def accept(@Nonnull clazz:Class[_]):Boolean = typeMap.keySet.contains(clazz)

  def cast[T](@Nonnull clazz:Class[T], @Nullable value:Any):Option[T] = typeMap.get(clazz).flatMap { f =>
    try {
      Some(clazz.cast(f.apply(value)))
    } catch {
      case _:MatchError => None
    }
  }

  def typedef[T](@Nonnull clazz:Class[T])(@Nonnull f:PartialFunction[Any, T]):Unit = {
    typeMap.put(clazz, typeMap.get(clazz) match {
      case Some(pf) => pf.orElse(f)
      case None => f
    })
  }

}

object Transformer {
  private[this] val logger = LoggerFactory.getLogger(classOf[Transformer])

  private[this] val transformers = ServiceLoader.load(classOf[Transformer]).asScala.toList

  @throws[CodecException]
  def transform[T](@Nonnull clazz:Class[T], @Nullable value:Any):T = {

    // null 値に対するプリミティブ型の暗黙的ゼロ変換
    if(value == null) {
      return clazz.cast(ZERO.get(clazz).orNull)
    }

    // 形と互換性のある値の場合はそのまま返す
    if(value.getClass.isAssignableFrom(clazz)) {
      return clazz.cast(value)
    }

    // SPI に登録されている Transformer を使用して変換
    for(transformer <- transformers if transformer.accept(clazz)) {
      transformer.cast(clazz, value) match {
        case Some(obj) => return clazz.cast(obj)
        case None => None
      }
    }

    // Tuple や case class
    if(classOf[Product].isAssignableFrom(clazz)) {
      val params:Array[Any] = value match {
        case x:Array[_] => x.toSeq.toArray
        case x:Iterable[_] => x.toArray
        case x:java.util.Collection[_] => x.asScala.toArray
        case x => Array(x)
      }
      Option(clazz.getConstructors).getOrElse(Array.empty).filter(_.getParameterCount == params.length).foreach { con =>
        val types = con.getParameterTypes
        try {
          return clazz.cast(con.newInstance(types.zip(params).map { case (t, p) => transform(t, p) }))
        } catch {
          case ex:Exception =>
            logger.trace(s"${Debug.getSimpleName(con)} x (${params.map(Debug.toString).mkString(",")}) => $ex")
        }
      }
    }

    // Java Bean Style
    Try(clazz.getConstructor()).toOption.flatMap { con =>
      (value match {
        case m:Map[_, _] => Some(m)
        case m:java.util.Map[_, _] => Some(m.asScala.toMap)
        case _ => None
      }).flatMap {
        case m:Map[_, _] if m.keySet.forall(s => s.isInstanceOf[String] && s.asInstanceOf[String].nonEmpty) =>
          Some(m.map(x => (x._1.toString, x._2)))
        case _ => None
      }.map(props => (con, props))
    }.foreach { case (con, props) =>
      newBean(con, props).foreach {
        return _
      }
    }

    throw new CodecException(s"cannot transform the value [$value] to specified type ${clazz.getSimpleName}")
  }

  private[this] val ZERO:Map[Class[_], AnyRef] = Map(
    classOf[Boolean] -> java.lang.Boolean.FALSE,
    classOf[Byte] -> java.lang.Byte.valueOf(0.toByte),
    classOf[Char] -> java.lang.Character.valueOf(0.toChar),
    classOf[Short] -> java.lang.Short.valueOf(0.toShort),
    classOf[Int] -> java.lang.Integer.valueOf(0),
    classOf[Long] -> java.lang.Long.valueOf(0.toLong),
    classOf[Float] -> java.lang.Float.valueOf(0.toFloat),
    classOf[Double] -> java.lang.Double.valueOf(0.toDouble),
    java.lang.Boolean.TYPE -> java.lang.Boolean.FALSE,
    java.lang.Byte.TYPE -> java.lang.Byte.valueOf(0.toByte),
    java.lang.Character.TYPE -> java.lang.Character.valueOf(0.toChar),
    java.lang.Short.TYPE -> java.lang.Short.valueOf(0.toShort),
    java.lang.Integer.TYPE -> java.lang.Integer.valueOf(0),
    java.lang.Long.TYPE -> java.lang.Long.valueOf(0),
    java.lang.Float.TYPE -> java.lang.Float.valueOf(0),
    java.lang.Double.TYPE -> java.lang.Double.valueOf(0)
  )

  private[this] val METHODS = new ConcurrentHashMap[Class[_], Map[String, Seq[Method]]]()

  private[this] def newBean[T](con:Constructor[T], props:Map[String, Any]):Option[T] = {
    val methods = METHODS.computeIfAbsent(con.getDeclaringClass, { clazz =>
      Option(clazz.getMethods)
        .getOrElse(Array.empty)
        .filter(_.getParameterCount == 1)
        .groupBy(_.getName)
        .mapValues(_.toSeq)
    })
    var obj:Option[T] = Some(con.newInstance())
    props.foreach { case (key, value) =>
      obj.foreach { o =>
        val setter = key.headOption.map(head => "set" + Character.toUpperCase(head) + key.drop(1))
        val set = (methods.getOrElse(key, Seq.empty) ++ setter.flatMap(methods.get).getOrElse(Seq.empty)).exists { method =>
          Try {
            val paramType = method.getParameterTypes.head
            val param:Object = anyToObject(transform(paramType, value))
            method.invoke(o, param)
          }.isSuccess
        }
        if(!set) {
          logger.trace(s"cannot set value ${Debug.toString(value)} to property '$key` of class ${con.getDeclaringClass.getSimpleName}")
          obj = None
        }
      }
    }
    obj
  }

  def anyToObject(value:Any):Object = value match {
    case null => null
    case x:Boolean => java.lang.Boolean.valueOf(x)
    case x:Byte => java.lang.Byte.valueOf(x)
    case x:Short => java.lang.Short.valueOf(x)
    case x:Int => java.lang.Integer.valueOf(x)
    case x:Long => java.lang.Long.valueOf(x)
    case x:Float => java.lang.Float.valueOf(x)
    case x:Double => java.lang.Double.valueOf(x)
    case x:Char => java.lang.Character.valueOf(x)
    case x:Object => x
    case x => throw new CodecException(s"unexpected type to convert Object: ${Debug.toString(x)}")
  }

}