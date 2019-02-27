package io.asterisque.core.msg

import java.lang.reflect.Modifier

import org.specs2.Specification

class AbortSpec extends Specification {
  def is =
    s2"""
Abort is declared as final. ${Modifier.isFinal(classOf[Abort].getModifiers) must beTrue}
Abort has properties that specified in constructor. $e0
Abort throws IllegalArgumentException when message is null. ${new Abort(100, null) must throwA[NullPointerException]}
equals. $equals
"""

  private[this] def e0 = {
    val a = new Abort(100, "hoge")
    (a.code === 100) and (a.message === "hoge")
  }

  private[this] def equals = {
    new Abort(100, "hoge").equals(null) must beFalse
  }
}
