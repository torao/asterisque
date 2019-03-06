package io.asterisque.wire.rpc;

import io.asterisque.Priority;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author Takami Torao
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Export {
  short value();
  byte priority() default Priority.Normal;
}
