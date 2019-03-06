package io.asterisque.wire.message

import java.lang.reflect.Modifier

import org.specs2.Specification

class MessageSpec extends Specification {
  def is =
    s2"""
Message should:
declare as abstract class. $messageIsInterface
"""

  private[this] def messageIsInterface = {
    Modifier.isInterface(classOf[Message].getModifiers) must beTrue
  }
}
