/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import org.slf4j.LoggerFactory

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// TypeMapper
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
object TypeMapper {
	private[TypeMapper] val logger = LoggerFactory.getLogger(TypeMapper.getClass)

	class Exception(msg:String) extends java.lang.Exception(msg)

	def appropriateValue[T](value:AnyRef, to:Class[T]):T = {
		mapper.get(to) match {
			case Some(f) =>
				if(f.isDefinedAt(value)){
					to.cast(f(value))
				} else {
					throw new Exception(s"type cannot be compatible: ${value} to ${to.getSimpleName}")
				}
			case None =>
				throw new Exception(s"type cannot be compatible: ${value} to ${to.getSimpleName}")
		}
	}


	var mapper = Map[Class[_], PartialFunction[AnyRef,_]]()

	class Mapper[T](to:Class[T]){
		def from(f:PartialFunction[AnyRef,T]) = {
			mapper += to -> f
		}
	}

	def to[T](clazz:Class[T]) = new Mapper(clazz)

	to(classOf[Boolean]) from {
		case i:Number => i != 0
		case s:String => s.toBoolean
	}
	to(classOf[Byte]) from {
		case i:Number => i.byteValue()
		case s:String => s.toByte
	}
	to(classOf[Short]) from {
		case i:Number => i.shortValue()
		case s:String => s.toShort
	}
	to(classOf[Int]) from {
		case i:Number => i.intValue()
		case s:String => s.toInt
	}
	to(classOf[Long]) from {
		case i:Number => i.longValue()
		case s:String => s.toLong
	}
	to(classOf[Float]) from {
		case i:Number => i.floatValue()
		case s:String => s.toFloat
	}
	to(classOf[Double]) from {
		case i:Number => i.doubleValue()
		case s:String => s.toDouble
	}
	to(classOf[String]) from {
		case s:String => s
		case value => value.toString
	}
	to(classOf[Array[_]]) from {
		case a:Array[_] => a
		case s:Seq[_] => s.map{ _.asInstanceOf[AnyRef] }.toArray[AnyRef]
		case value => Array(value)
	}
	to(classOf[List[_]]) from {
		case a:Array[_] => a.toList
		case l:List[_] => l
		case s:Seq[_] => s.toList
		case value => List(value)
	}
	to(classOf[Map[_,_]]) from {
		case m:Map[_,_] => m
	}

}
