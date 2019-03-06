package io.asterisque.wire.rpc

import javax.annotation.Nonnull

import scala.concurrent.{ExecutionContext, Future}

trait Service extends ((Pipe, ExecutionContext) => Future[Any]) {
  def apply(@Nonnull pipe:Pipe, @Nonnull executor:ExecutionContext):Future[Any] = {
    val msg = f"function not found: ${pipe.function & 0xFFFF}%d"
    Future.failed(new NoSuchFunctionException(msg))
  }
}
