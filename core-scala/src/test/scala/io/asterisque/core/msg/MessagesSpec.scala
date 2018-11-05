package io.asterisque.msg

import java.lang.reflect.Modifier

import org.specs2.Specification

import scala.util.Random

class MessagesSpec extends Specification {
  def is =
    s2"""
Block should:
declare as final. ${Modifier.isFinal(classOf[Block].getModifiers) must beTrue}
throw exception for illegal arguments in constructor. $blockConstructor
be eof of block of Block.eof(). ${(Block.eof(1).eof must beTrue) and (Block.eof(1).length == 0) and (Block.eof(1).loss === 0)}
return byte-array as same as specified to constructor. $blockRetrievePayload
return text as string. $blockGetTextString

Close should:
declare as final. ${Modifier.isFinal(classOf[Close].getModifiers) must beTrue}
have properties that specified in constructor. $closeProperties
create unexpected error. $closeError

Open should:
declare as final class. ${Modifier.isFinal(classOf[Open].getModifiers) must beTrue}
have properties these are specified in constructor. $openProperties
throw NullPointerException if data is null. ${new Open(1, 8, 12, null) must throwA[NullPointerException]}
"""

  private[this] def blockConstructor = {
    (new Block(1, null, 0, 0) must throwA[NullPointerException]) and
      (new Block(1, new Array[Byte](0), 1, 1) must throwA[IllegalArgumentException]) and
      (new Block(1, new Array[Byte](10), -1, 1) must throwA[IllegalArgumentException]) and
      (new Block(1, new Array[Byte](10), 1, -1) must throwA[IllegalArgumentException]) and
      (new Block(1, new Array[Byte](Block.MaxPayloadSize + 1), 0, Block.MaxPayloadSize + 1) must throwA[IllegalArgumentException])
  }

  private[this] def blockRetrievePayload = {
    val offset = 10
    val r = new Random(12345)
    val b = new Array[Byte](256)
    r.nextBytes(b)
    val buffer1 = new Block(1, b, 0, b.length).toByteBuffer
    val buffer2 = new Block(1, b, offset, b.length - offset).toByteBuffer
    (b.length === buffer1.remaining()) and
      b.indices.map { i => b(i) === buffer1.get(i) }.reduce {
        _ and _
      } and
      (buffer2.position() === offset) and
      ((b.length - offset) === buffer2.remaining()) and
      (0 until (b.length - offset)).map { i => b(i + offset) === buffer2.get(buffer2.position() + i) }.reduce {
        _ and _
      }
  }

  private[this] def blockGetTextString = {
    val t = "あいうえお"
    val b = t.getBytes("UTF-8")
    val text = new Block(1, b, 0, b.length).getString
    text === t
  }

  private[this] def closeProperties = {
    val c0 = new Close(1.toShort, "hoge")
    val c1 = new Close(2.toShort, new Abort(300, "foo"))
    (c0.result === "hoge") and (c0.abort must beNull) and
      (c1.abort !=== null) and (c1.abort.message === "foo")
  }

  private[this] def closeError = {
    val c = Close.unexpectedError(1, "error")
    (c.pipeId === 1) and (c.abort !=== null) and (c.abort.message === "error")
  }

  private[this] def openProperties = {
    val args:Array[AnyRef] = Array("A", Integer.valueOf(2))
    val o = new Open(1, 8, 12, args)
    (o.pipeId === 1) and (o.priority === 8) and (o.functionId === 12) and o.params.zip(args).map { case (a, b) => a === b }.reduce {
      _ and _
    }
  }

}
