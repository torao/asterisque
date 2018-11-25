package io.asterisque.core.service;

import io.asterisque.Priority;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * function として外部からの呼び出しを許可するメソッドに対して付加するアノテーション。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Export {

  /**
   * function 番号に相当する値。
   * @return function 番号
   */
  short value();

  /**
   * このメソッド呼び出しのプライオリティ。
   * @return プライオリティ
   */
  byte priority() default Priority.Normal;
}
