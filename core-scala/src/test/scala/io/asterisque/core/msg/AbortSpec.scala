/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.msg

import java.lang.reflect.Modifier

import org.specs2.Specification

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// AbortSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class AbortSpec extends Specification { def is = s2"""
Abort should:
declare as final. ${Modifier.isFinal(classOf[Abort].getModifiers) must beTrue}
have properties that specified in constructor. $e0
throw NullPointerException when message is null. ${new Abort(100, null) must throwA[NullPointerException]}
"""

  def e0 = {
    val a = new Abort(100, "hoge")
    (a.code === 100) and (a.message === "hoge")
  }
}
