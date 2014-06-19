/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org

import java.net.SocketAddress
import java.util.Optional

import scala.concurrent.{Promise, Future}
import java.util.function._
import java.util.concurrent.CompletableFuture

package object asterisque {

	implicit def Function0ToSupplier[T](f:()=>T) = new Supplier[T] {
		override def get():T = f()
	}
	implicit def Function1ToConsumer[T](f:T=>Unit) = new Consumer[T]{
		override def accept(t:T):Unit = f(t)
		override def andThen(after:Consumer[_ >: T]):Consumer[T] = super.andThen(after)
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
	implicit class J2SCompletableFuture[T](future:CompletableFuture[T]) {
		def toFuture:Future[T] = {
			val promise = Promise[T]()
			val f = Function2ToBiConsumer({ (t:T, ex:Throwable) =>
				if(ex == null) promise.success(t) else promise.failure(ex)
			})
			future.whenComplete(f)
			promise.future
		}
	}

	implicit class RichLocalHost(local:LocalNode) {
		def listen(address:SocketAddress, config:Options)(onAccept:(Session)=>Unit):Future[Bridge.Server] = {
			local.listen(address, config, onAccept).toFuture
		}
	}

}
