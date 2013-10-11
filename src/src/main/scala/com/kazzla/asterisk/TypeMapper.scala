/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import org.slf4j.LoggerFactory
import scala.collection.JavaConversions._

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// TypeMapper
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * リモートメソッド呼び出しのために転送から復元された値を実際の呼び出しパラメータとして使用できる型に変換します。
 * 型 A から型 B への新しい型変換を追加する場合は以下のように記述します。
 * {{{
 *   TypeMapper.to(classOf[B]) from {
 *     case i:B => i.toA
 *     ... // 想定しうる変換可能な型
 *   }
 * }}}
 * @author Takami Torao
 */
object TypeMapper {
	private[TypeMapper] val logger = LoggerFactory.getLogger(TypeMapper.getClass)

	class Exception(msg:String) extends java.lang.Exception(msg)

	// ==============================================================================================
	// 適切な型への変換
	// ==============================================================================================
	/**
	 * 指定された値を型の違う同値に変換します。
	 * @param value 変換する値
	 * @param to 変換後の値
	 * @return 変換結果
	 */
	def appropriateValue[T](value:Any, to:Class[T]):T = {

		// 型がおなじ場合は無変換
		if(value.getClass == to){
			to.cast(value)
		}

		mapper.get(to) match {
			case Some(f) =>
				if(f.isDefinedAt(value)){
					to.cast(f(value))
				} else {
					throw new Exception(s"type cannot be compatible: $value to ${to.getSimpleName}")
				}
			case None =>
				throw new Exception(s"type cannot be compatible: $value to ${to.getSimpleName}")
		}
	}

	/**
	 * 変換後の型による変換処理のマップ。
	 */
	private[this] var mapper = Map[Class[_], PartialFunction[Any,_]]()

	class Mapper[T] private[TypeMapper](to:Class[T]){
		def from(f:PartialFunction[Any,T]) = mapper += to -> f
	}

	// ==============================================================================================
	// 型変換の定義
	// ==============================================================================================
	/**
	 * 指定された型へ変換するためのマッパーを追加します。`from` に続いて部分関数を指定することで任意の型からの変換
	 * を定義します。
	 * {{{
	 *   TypeMapper.to(classOf[A]) from {
	 *     case i:Boolean => i.toA
	 *     case i:String => i.toA
	 *     // ...
	 *   }
	 * }}}
	 * @param clazz 変換後に期待する型のクラス
	 * @tparam T 変換後の型
	 * @return
	 */
	def to[T](clazz:Class[T]) = new Mapper(clazz)

	to(classOf[Boolean]) from {
		case i:Number => i.intValue() != 0
		case s:String => s.toBoolean
	}
	to(classOf[Byte]) from {
		case i:Boolean => if(i) 1 else 0
		case i:Number => i.byteValue()
		case s:String => s.toByte
	}
	to(classOf[Short]) from {
		case i:Boolean => if(i) 1 else 0
		case i:Number => i.shortValue()
		case s:String => s.toShort
	}
	to(classOf[Int]) from {
		case i:Boolean => if(i) 1 else 0
		case i:Number => i.intValue()
		case s:String => s.toInt
	}
	to(classOf[Long]) from {
		case i:Boolean => if(i) 1 else 0
		case i:Number => i.longValue()
		case s:String => s.toLong
	}
	to(classOf[Float]) from {
		case i:Boolean => if(i) 1 else 0
		case i:Number => i.floatValue()
		case s:String => s.toFloat
	}
	to(classOf[Double]) from {
		case i:Boolean => if(i) 1 else 0
		case i:Number => i.doubleValue()
		case s:String => s.toDouble
	}
	to(classOf[Char]) from {
		case i:Boolean => if(i) '1' else '0'
		case i:Number => (i.shortValue() & 0xFFFF).toChar
		case s:String => if(s.length > 0) s.charAt(0) else '\0'
	}
	to(classOf[String]) from {
		case a:Array[_] => a.map{ _.toString }.mkString
		case value => value.toString
	}
	to(classOf[Array[Byte]]) from {
		case i:Array[Byte] => i
		case i:Seq[_] => i.map{ value => appropriateValue(value, classOf[Byte]) }.toArray
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
		case l:java.util.List[_] => l.toList
		case value => List(value)
	}
	to(classOf[java.util.List[_]]) from {
		case l:java.util.List[_] => l
		case a:Array[_] => a.toList
		case l:List[_] => l
		case s:Seq[_] => s.toList
		case value => List(value)
	}
	to(classOf[Map[_,_]]) from {
		case m:Map[_,_] => m
		case m:java.util.Map[_,_] => m.toMap
	}

}
