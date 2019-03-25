package io.asterisque

import java.util.concurrent.CompletableFuture

import javax.annotation.Nonnull

import scala.concurrent.{Future, Promise}

object Scala {

  implicit class _CompletableFuture2Future[T](@Nonnull future:CompletableFuture[T]) {
    def asScala:Future[T] = {
      val promise = Promise[T]()
      future.whenComplete((r, ex) => if(ex != null) promise.failure(ex) else promise.success(r))
      promise.future
    }
  }

}
