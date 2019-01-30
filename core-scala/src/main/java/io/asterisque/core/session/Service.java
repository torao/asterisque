package io.asterisque.core.session;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public interface Service {
  default CompletableFuture<Object> apply(@Nonnull Pipe pipe, @Nonnull Executor executor) {
    CompletableFuture<Object> future = new CompletableFuture<>();
    String msg = String.format("function not found: %d", pipe.functionId() & 0xFFFF);
    future.completeExceptionally(new NoSuchFunctionException(msg));
    return future;
  }
}
