package io.asterisque.wire.message

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

import io.asterisque.wire.message.Message.{Close, Open}
import org.specs2.Specification

abstract class AbstractMessageSpec extends Specification {
  def is =
    s2"""
toString() without null or Exception: $toStringWithoutNullOrException
generate the same object by serialization: $serializeRestoration
Message returns false if compare with null or other object. $falseIfCompareWithNullOrElse
"""

  private[this] def toStringWithoutNullOrException = newMessages.map { msg =>
    msg.toString !== null
  }.reduceLeft(_ and _)

  private[this] def serializeRestoration = newMessages.map { expected =>
    val baos = new ByteArrayOutputStream()
    val out = new ObjectOutputStream(baos)
    out.writeObject(expected)
    out.close()
    val bais = new ByteArrayInputStream(baos.toByteArray)
    val in = new ObjectInputStream(bais)
    val actual = in.readObject()
    (expected.hashCode() === actual.hashCode()).setMessage(s"$expected != $actual") and
      (expected === actual).setMessage(s"$expected != $actual")
  }.reduceLeft(_ and _)

  private[this] def falseIfCompareWithNullOrElse = newMessages.map { msg =>
    (msg.equals(null) must beFalse) and
      (msg.equals(new Object()) must beFalse) and
      (msg.equals(if(msg.isInstanceOf[Open]) Close(0, Array.empty) else Open(0, 0, Array.empty)) must beFalse) and
      (msg.equals("foo".asInstanceOf[Object]) must beFalse)
  }.reduceLeft(_ and _)

  /**
    * 共通テストに使用する典型的なメッセージをサブクラスによって作成する。
    */
  protected def newMessages:Iterable[Message]
}
