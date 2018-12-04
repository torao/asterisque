package io.asterisque

import java.util

import io.asterisque.core.msg._
import org.specs2.execute._
import org.specs2.Specification
import org.specs2.specification.core.SpecStructure

package object test {

  def randomString(seed:Int, length:Int):String = {
    val random = new scala.util.Random(seed)
    random.nextString(length)
  }

}
