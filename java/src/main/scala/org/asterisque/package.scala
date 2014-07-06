/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org

import java.net.SocketAddress
import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean

import org.asterisque.codec.{Struct, TypeConversion}
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

	implicit def Function0ToRunnable(f:()=>Unit) = new Runnable {
		override def run():Unit = f()
	}
	implicit def Function0ToSupplier[T](f:()=>T) = new Supplier[T] {
		override def get():T = f()
	}
	implicit def Function1ToConsumer[T](f:T=>Unit) = new Consumer[T]{
		override def accept(t:T):Unit = f(t)
		override def andThen(after:Consumer[_ >: T]):Consumer[T] = super.andThen(after)
	}
	implicit def Function1ToFunction[T,U](f:T=>U) = new Function[T,U]{
		override def apply(t:T):U = f(t)
		override def compose[V](before:Function[_ >: V,_ <: T]):Function[V,U] = super.compose(before)
		override def andThen[V](after:Function[_ >: U,_ <: V]):Function[T,V] = super.andThen(after)
	}
	implicit def Function2ToBiConsumer[T,U](f:(T,U)=>Unit) = new BiConsumer[T,U]{
		override def accept(t:T, u:U):Unit = f(t, u)
		override def andThen(after:BiConsumer[_ >: T, _ >: U]):BiConsumer[T, U] = super.andThen(after)
	}
	implicit def Function2ToBiFunction[T,U,R](f:(T,U)=>R) = new BiFunction[T,U,R]{
		override def apply(t:T, u:U):R = f(t, u)
		override def andThen[V](after:Function[_ >: R, _ <: V]):BiFunction[T, U, V] = super.andThen(after)
	}

	implicit def IntToInteger(i:Int) = Integer.valueOf(i)

	/** Java Optional to Scala Option */
	implicit def OptionalToOption[T](option:Optional[T]) = if(option.isPresent) Some(option.get()) else None

	/** Java CompletableFuture to Scala Future */
	implicit def CompletableFuture2Future[T](future:CompletableFuture[T]):Future[T] = {
		val promise = Promise[T]()
		val f = Function2ToBiConsumer({ (t:T, ex:Throwable) =>
			if(promise.isCompleted) {
				logger.warn(s"CompletableFuture callback two or more call: result=${Debug.toString(t)}, exception=$ex")
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
		def listen(address:SocketAddress, config:Options)(onAccept:(Session)=>Unit):Future[Bridge.Server] = {
			local.listen(address, config, onAccept)
		}
	}



	private[this] val _init = new AtomicBoolean(false)
	def init():Unit = if(_init.compareAndSet(false, true)){
		TypeConversion.addExtension(new TypeConversion(){
			import java.util.{Map => JMap, List => JList}
			logger.debug("installing scala type-conversion")
			setFromTo(classOf[Map[_,_]], classOf[JMap[_,_]],
				{m:Map[_,_] => JavaConversions.mapAsJavaMap(m)},
				{m:JMap[_,_] => m.toMap})
			// 優先順位: Map > Seq
			setFromTo(classOf[Seq[_]], classOf[JList[_]],
				{m:Seq[_] => JavaConversions.seqAsJavaList(m)},
				{m:JList[_] => m.toSeq})
			/*
			setFromTo(classOf[Boolean], classOf[java.lang.Boolean],
				{s:Boolean => if(s) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE},
				{s:java.lang.Boolean => if(s) true else false})
			*/
			setTransferConversion(Function1ToFunction({
				case _:BoxedUnit => Optional.of(java.util.Collections.emptyList())
				case _ => Optional.empty()}))
			// :: のように Seq と Product 両方を実装するクラスが標準であるので Product の優先順位を低くする
			setTransferConversion(classOf[Product],
			{s:Product => new Struct {
				def schema:String = s.getClass.getName
				def count:Int = s.productArity
				def valueAt(i:Int):AnyRef = TypeConversion.toTransfer(s.productElement(i))
			}
			})

			def _setFromTo[FROM <: Any,TO <: Any](from:Class[FROM],
				to:Class[TO], conv:(FROM)=>TO, rev:(TO)=>FROM):Unit = {
				setFromTo(from, to, conv, rev)
			}
		})
	}
	init()

}
