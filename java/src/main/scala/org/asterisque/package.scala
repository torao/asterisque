/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org

import java.net.SocketAddress
import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean

import org.asterisque.codec.TypeConversion
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions
import scala.collection.JavaConversions._
import scala.concurrent.{Promise, Future}
import java.util.function._
import java.util.concurrent.CompletableFuture

import scala.runtime.BoxedUnit
import scala.util.{Failure, Success}

package object asterisque {
	val logger = LoggerFactory.getLogger("org.asterisque.package")

	import scala.language.implicitConversions

	implicit def Function0ToRunnable(f:() => Unit) = new Runnable {
		override def run():Unit = f()
	}

	implicit def Function0ToSupplier[T](f:() => T) = new Supplier[T] {
		override def get():T = f()
	}

	implicit def Function1ToConsumer[T](f:T => Unit) = new Consumer[T] {
		override def accept(t:T):Unit = f(t)

		override def andThen(after:Consumer[_ >: T]):Consumer[T] = super.andThen(after)
	}

	implicit def Function1ToFunction[T, U](f:T => U) = new Function[T, U] {
		override def apply(t:T):U = f(t)

		override def compose[V](before:Function[_ >: V, _ <: T]):Function[V, U] = super.compose(before)

		override def andThen[V](after:Function[_ >: U, _ <: V]):Function[T, V] = super.andThen(after)
	}

	implicit def Function2ToBiConsumer[T, U](f:(T, U) => Unit) = new BiConsumer[T, U] {
		override def accept(t:T, u:U):Unit = f(t, u)

		override def andThen(after:BiConsumer[_ >: T, _ >: U]):BiConsumer[T, U] = super.andThen(after)
	}

	implicit def Function2ToBiFunction[T, U, R](f:(T, U) => R) = new BiFunction[T, U, R] {
		override def apply(t:T, u:U):R = f(t, u)

		override def andThen[V](after:Function[_ >: R, _ <: V]):BiFunction[T, U, V] = super.andThen(after)
	}

	implicit def IntToInteger(i:Int) = Integer.valueOf(i)

	/** Java Optional to Scala Option */
	implicit def OptionalToOption[T](option:Optional[T]) = if (option.isPresent) Some(option.get()) else None

	/** Java CompletableFuture to Scala Future */
	implicit def CompletableFuture2Future[T](future:CompletableFuture[T]):Future[T] = {
		val promise = Promise[T]()
		val f = Function2ToBiConsumer({ (t:T, ex:Throwable) =>
			if (promise.isCompleted) {
				logger.warn(s"CompletableFuture callback two or more call: result=${Debug.toString(t) }, exception=$ex")
			} else {
				if (ex == null) {
					promise.success(t)
				} else {
					promise.failure(ex)
				}
			}
		})
		future.whenComplete(f)
		promise.future
	}

	/** Java CompletableFuture to Scala Future */
	implicit class RichCompletableFuture[T](future:CompletableFuture[T]) {
		def toFuture:Future[T] = CompletableFuture2Future(future)
	}

	/** Scala Future to Java CompletableFuture */
	implicit def Future2CompletableFuture[T](future:Future[T]):CompletableFuture[T] = {
		import scala.concurrent.ExecutionContext.Implicits.global
		val cf = new CompletableFuture[T]()
		future.onComplete {
			case Success(result) => cf.complete(result)
			case Failure(ex) => cf.completeExceptionally(ex)
		}
		cf
	}

	implicit class RichLocalHost(local:Node) {
		def listen(address:SocketAddress, config:Options)(onAccept:(Session) => Unit):Future[Bridge.Server] = {
			local.listen(address, config, onAccept)
		}
	}

	private[this] val _init = new AtomicBoolean(false)

	/**
	 * Scala 固有のオブジェクトを転送可能にするための型変換拡張。
	 */
	private[this] object ScalaConversion extends TypeConversion {
		import java.lang.{Boolean => JBoolean, Byte => JByte, Short => JShort, Integer => JInt, Long => JLong, Float => JFloat, Double => JDouble}
		import java.util.{Map => JMap, List => JList}
		try {
			logger.debug("installing scala type-conversion")
			setFromTo(classOf[Unit], classOf[Void],
			{ i:Unit => null:Void }, { i:Void => () })
			setFromTo(classOf[BoxedUnit], classOf[Void],
			{ i:BoxedUnit => null:Void }, { i:Void => BoxedUnit.UNIT })
			setFromTo(classOf[Boolean], classOf[JBoolean],
			{ i:Boolean => Boolean.box(i) }, { i:JBoolean => if (i) true else false })
			setFromTo(classOf[Byte], classOf[JByte],
			{ i:Byte => Byte.box(i) }, { i:JByte => i.toByte })
			setFromTo(classOf[Short], classOf[JShort],
			{ i:Short => Short.box(i) }, { i:JShort => i.toShort })
			setFromTo(classOf[Int], classOf[JInt],
			{ i:Int => Int.box(i) }, { i:JInt => i.toInt })
			setFromTo(classOf[Long], classOf[JLong],
			{ i:Long => Long.box(i) }, { i:JLong => i.toLong })
			setFromTo(classOf[Float], classOf[JFloat],
			{ i:Float => Float.box(i) }, { i:JFloat => i.toFloat })
			setFromTo(classOf[Double], classOf[JDouble],
			{ i:Double => Double.box(i) }, { i:JDouble => i.toDouble })
			// Map ⇄ java.util.Map
			setFromTo(classOf[Map[_, _]], classOf[JMap[_, _]],
			{ m:Map[_, _] => JavaConversions.mapAsJavaMap(m) }, { m:JMap[_, _] => m.toMap })
			// Seq, List ⇄ java.util.List (優先順位: Map > Seq)
			setFromTo(classOf[Seq[_]], classOf[JList[_]],
			{ m:Seq[_] => JavaConversions.seqAsJavaList(m) }, { m:JList[_] => m.toSeq })
			// java.util.List → List
			setMethodCallConversion(classOf[JList[_]], classOf[List[_]],
			{ m:JList[_] => m.toList })
			// Set ⇄ java.util.List
			setFromTo(classOf[Set[_]], classOf[JList[_]],
			{ m:Set[_] => JavaConversions.seqAsJavaList(m.toSeq) }, { m:JList[_] => m.toSet })
			/*
			// Unit ⇄ java.util.List
			setTransferConversion(Function1ToFunction({
				case _:BoxedUnit => Optional.of(java.util.Collections.emptyList())
				case _ => Optional.empty()
			}))
			setMethodCallConversion(classOf[JList[_]], classOf[Unit], { _:JList[_] => () })
			setMethodCallConversion(null, classOf[Unit], { _:Null => () })
			*/
			// Product ⇄ Struct
			// :: のように Seq と Product 両方を実装するクラスが標準であるので Product の優先順位を低くする
			setTransferConversion(classOf[Product], { s:Product => new Tuple {
				def schema:String = s.getClass.getName
				def count:Int = s.productArity
				def valueAt(i:Int):AnyRef = TypeConversion.toTransfer(s.productElement(i))
			}
			})

			// Tuple ⇄ Tuple
			def tupleConversion(value:AnyRef, clazz:Class[_]):Optional[AnyRef] = {
				value match {
					case tuple:Tuple if classOf[Product].isAssignableFrom(clazz) =>
						if(classOf[Tuple1[_]].isAssignableFrom(clazz)) {
							Optional.of(Tuple1(tuple.valueAt(0)))
						} else if(classOf[Tuple2[_,_]].isAssignableFrom(clazz)) {
							Optional.of(Tuple2(tuple.valueAt(0), tuple.valueAt(1)))
						} else if(classOf[Tuple3[_,_,_]].isAssignableFrom(clazz)) {
							Optional.of(Tuple3(tuple.valueAt(0), tuple.valueAt(1), tuple.valueAt(2)))
						} else {
							// TODO
							Optional.empty()
						}
					case _ => Optional.empty()
				}
			}
			setMethodCallConversion(Function2ToBiFunction(tupleConversion))
		} catch {
			case ex:Exception =>
				ex.printStackTrace()
				throw ex
		}
	}
	def init():Unit = if(_init.compareAndSet(false, true)){
		TypeConversion.addExtension(ScalaConversion)
	}
	init()

}
