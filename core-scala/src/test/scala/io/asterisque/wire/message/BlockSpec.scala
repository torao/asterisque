package io.asterisque.wire.message

import java.lang.reflect.Modifier

import io.asterisque.Asterisque
import io.asterisque.wire.message.Message.Block
import org.specs2.specification.core.SpecStructure

import scala.util.Random

class BlockSpec extends AbstractMessageSpec {
  override def is:SpecStructure = super.is append
    s2"""
Block should:
declare as final. ${Modifier.isFinal(classOf[Block].getModifiers) must beTrue}
throw wsCaughtException for illegal arguments in constructor. $blockConstructor
be eof of block of Block.eof(). ${(Block.eof(1).eof must beTrue) and (Block.eof(1).length == 0) and (Block.eof(1).loss === 0)}
return byte-array as same as specified to constructor. $blockRetrievePayload
return text as string. $blockGetTextString
equals. $blockEquals
"""

  private[this] def blockConstructor = (Block(1, null, 0, 0) must throwA[NullPointerException]) and
    (Block(1, new Array[Byte](0), 0, 1) must throwA[IllegalArgumentException]) and
    (Block(1, new Array[Byte](0), 1, 1) must throwA[IllegalArgumentException]) and
    (Block(1, new Array[Byte](10), -1, 1) must throwA[IllegalArgumentException]) and
    (Block(1, new Array[Byte](10), 1, -1) must throwA[IllegalArgumentException]) and
    (Block(1, new Array[Byte](Block.MaxPayloadSize + 1), 0, Block.MaxPayloadSize + 1) must throwA[IllegalArgumentException]) and
    (Block(1, -1, new Array[Byte](0), 0, 0, eof = false) must throwA[IllegalArgumentException]) and {
    Block(1, 0, new Array[Byte](0), 0, 0)
    success
  }

  private[this] def blockRetrievePayload = {
    val offset = 10
    val r = new Random(12345)
    val b = new Array[Byte](256)
    r.nextBytes(b)
    val buffer1 = Block(1, b, 0, b.length).toByteBuffer
    val buffer2 = Block(1, b, offset, b.length - offset).toByteBuffer
    (b.length === buffer1.remaining()) and
      b.indices.map { i => b(i) === buffer1.get(i) }.reduce(_ and _) and
      (buffer2.position() === offset) and
      ((b.length - offset) === buffer2.remaining()) and
      (0 until (b.length - offset)).map { i => b(i + offset) === buffer2.get(buffer2.position() + i) }.reduceLeft(_ and _)
  }

  private[this] def blockGetTextString = {
    val t = "あいうえお"
    val b = t.getBytes("UTF-8")
    val text = Block(1, b, 0, b.length).getString
    text === t
  }

  private[this] def blockEquals = {
    val buffer = (0 to 0xFF).map(_.toByte).toArray
    val base = Block(0, buffer, 0, 10)
    (base.equals(null) must beFalse) and
      (base.equals(Block(0, buffer, 0, 11)) must beFalse) and
      (base.equals(Block(0, buffer, 1, 10)) must beFalse)
  }

  protected override def newMessages:Seq[Message] = Seq(
    Block(0, 0, Asterisque.Empty.Bytes, 0, 0, eof = false),
    Block(1, 1, (0 to 0xFF).map(_.toByte).toArray, 0, 0x100, eof = false),
    Block(2, 2, (0 to Block.MaxPayloadSize).map(_.toByte).toArray, 0, Block.MaxPayloadSize, eof = false)
  )

}
