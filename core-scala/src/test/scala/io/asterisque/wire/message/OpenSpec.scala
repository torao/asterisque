package io.asterisque.wire.message

import java.lang.reflect.Modifier

import io.asterisque.test.randomByteArray
import io.asterisque.wire.message.Message.Open
import org.specs2.specification.core.SpecStructure

class OpenSpec extends AbstractMessageSpec {
  override def is:SpecStructure = super.is append
    s2"""
Open should:
declare as final class. ${Modifier.isFinal(classOf[Open].getModifiers) must beTrue}
have properties these are specified in constructor. $openProperties
throw NullPointerException if data is null. ${Open(1, 8, 12, null) must throwA[NullPointerException]}
"""

  private[this] def openProperties = {
    val params = randomByteArray(73840, 256)
    val o = Open(1, 8, 12, params)
    (o.pipeId === 1) and (o.priority === 8) and (o.functionId === 12) and (o.params === params)
  }

  protected override def newMessages:Seq[Message] = Seq(
    Open(0.toShort, 0.toShort, randomByteArray(738729, 256))
  )

}
