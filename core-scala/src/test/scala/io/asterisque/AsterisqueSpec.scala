/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque

import java.lang.reflect.Modifier

import org.specs2.Specification
import org.specs2.matcher.MatchResult

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// AsterisqueSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
abstract class AsterisqueSpec extends Specification {

  val True = true must beTrue

  def beStaticUtility(clazz:Class[_]):MatchResult[_] = {
    (Modifier.isFinal(clazz.getModifiers) must beTrue) and
    // 全てのフィールドが final 宣言されている
    clazz.getDeclaredFields.foldLeft(True){ (a, b) =>
      val m = b.getModifiers
      a and (Modifier.isPublic(m) must beTrue) and
        (Modifier.isStatic(m) must beTrue) and
        (Modifier.isFinal(m) must beTrue)
    } and
    // 全てのメソッドが static 宣言されている
    clazz.getDeclaredMethods.foldLeft(True){ (a, b) =>
      val m = b.getModifiers
      a and
        (Modifier.isPublic(m) must beTrue) and
        (Modifier.isStatic(m) must beTrue)
    } and
    // 全てのコンストラクタが private 宣言されている
    clazz.getDeclaredConstructors.foldLeft(True){ (a, b) =>
      val m = b.getModifiers
      a and
        (Modifier.isPrivate(m) must beTrue)
    }
  }
}
