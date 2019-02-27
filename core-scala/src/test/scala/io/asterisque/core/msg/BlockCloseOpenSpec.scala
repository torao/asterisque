package io.asterisque.core.msg

import java.lang.reflect.Modifier

import io.asterisque.Asterisque
import org.specs2.specification.core.SpecStructure

import scala.util.Random

class BlockCloseOpenSpec extends AbstractMessageSpec {
  override def is:SpecStructure = super.is append
    s2"""
Block should:
declare as final. ${Modifier.isFinal(classOf[Block].getModifiers) must beTrue}
throw wsCaughtException for illegal arguments in constructor. $blockConstructor
be eof of block of Block.eof(). ${(Block.eof(1).eof must beTrue) and (Block.eof(1).length == 0) and (Block.eof(1).loss === 0)}
return byte-array as same as specified to constructor. $blockRetrievePayload
return text as string. $blockGetTextString
equals. $blockEquals

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
      (new Block(1, new Array[Byte](0), 0, 1) must throwA[IllegalArgumentException]) and
      (new Block(1, new Array[Byte](0), 1, 1) must throwA[IllegalArgumentException]) and
      (new Block(1, new Array[Byte](10), -1, 1) must throwA[IllegalArgumentException]) and
      (new Block(1, new Array[Byte](10), 1, -1) must throwA[IllegalArgumentException]) and
      (new Block(1, new Array[Byte](Block.MaxPayloadSize + 1), 0, Block.MaxPayloadSize + 1) must throwA[IllegalArgumentException]) and
      (new Block(1, -1, new Array[Byte](0), 0, 0, false) must throwA[IllegalArgumentException]) and {
      new Block(1, 0, new Array[Byte](0), 0, 0)
      success
    }
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
      (0 until (b.length - offset)).map { i => b(i + offset) === buffer2.get(buffer2.position() + i) }.reduceLeft(_ and _)
  }

  private[this] def blockGetTextString = {
    val t = "あいうえお"
    val b = t.getBytes("UTF-8")
    val text = new Block(1, b, 0, b.length).getString
    text === t
  }

  private[this] def blockEquals = {
    val buffer = (0 to 0xFF).map(_.toByte).toArray
    val base = new Block(0, buffer, 0, 10)
    (base.equals(null) must beFalse) and
      (base.equals(new Block(0, buffer, 0, 11)) must beFalse) and
      (base.equals(new Block(0, buffer, 1, 10)) must beFalse)
  }

  private[this] def closeProperties = {
    val c0 = new Close(1.toShort, "hoge")
    val c1 = new Close(2.toShort, new Abort(300, "foo"))
    (c0.result === "hoge") and (c0.abort must beNull) and
      (c1.result must beNull) and (c1.abort.code === 300) and (c1.abort.message === "foo")
  }

  private[this] def closeError = {
    val c = Close.withError(1, "error")
    (c.pipeId === 1) and (c.abort.code === Abort.Unexpected) and (c.abort.message === "error")
  }

  private[this] def openProperties = {
    val args:Array[AnyRef] = Array("A", Integer.valueOf(2))
    val o = new Open(1, 8, 12, args)
    (o.pipeId === 1) and (o.priority === 8) and (o.functionId === 12) and o.params.zip(args).map { case (a, b) => a === b }.reduce {
      _ and _
    }
  }

  protected override def newMessages = Seq(
    new Block(0, 0, Asterisque.Empty.Bytes, 0, 0, false),
    new Block(1, 1, (0 to 0xFF).map(_.toByte).toArray, 0, 0x100, false),
    new Block(2, 2, (0 to Block.MaxPayloadSize).map(_.toByte).toArray, 0, Block.MaxPayloadSize, false),
    new Open(0, 0, Seq("A", Integer.valueOf(100), new java.util.Date()).toArray),
    new Close(0.toShort, "foo"),
    Close.withError(1, "bar")
  )

}
