package io.asterisque.utils

import org.specs2.Specification

import scala.collection.mutable

class EventDispatcherSpec extends Specification {
  def is =
    s2"""
It can add, remove and iterate listeners. $normalUse
It ignore exception in iteration of listners. $errorWhileIteration
"""

  private[this] def normalUse = {
    val target = new EventTarget()
    (0 until 10).foreach(i => target.addListener(i.toString))
    val listeners = target.fire()
    (0 until 10).foreach(i => target.removeListener(i.toString))
    val empty = target.fire()
    (empty.isEmpty must beTrue) and (listeners.size === 10) and
      listeners.sorted.zipWithIndex.map { case (s, i) => s.toInt === i }.reduceLeft(_ and _)
  }

  private[this] def errorWhileIteration = {
    val target = new EventTarget()
    target.addListener("FOO")
    target.ignoreException()
    success
  }

  class EventTarget extends EventDispatcher[String] {
    def fire():Seq[String] = {
      val listeners = mutable.Buffer[String]()
      foreach(x => listeners.append(x))
      listeners
    }

    def ignoreException():Unit = {
      foreach(_ => throw new NullPointerException())
    }
  }

}
