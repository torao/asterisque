package io.asterisque.core.msg

import java.lang.reflect.Modifier

import org.specs2.Specification

import scala.util.Random

class MessageSpec extends Specification { def is = s2"""
Message should:
declare as abstract class. $e0
keep pipe-id that is specified in constructor. $e1
throw exception for zero pipe-id expect Control. $e2
"""

  def e0 = Modifier.isAbstract(classOf[Message].getModifiers) must beTrue

  def e1 = {
    val random = new Random()
    1 to 10 map { _ =>
      val pipeId = random.nextInt().asInstanceOf[Short]
      val m = new Message(pipeId) { }
      m.pipeId === pipeId
    } reduce { (a, b) => a and b }
  }

  def e2 = {
    new Control(0, Array[Byte]()).pipeId === 0
  }
}
