package io.asterisque.wire.message

import java.lang.reflect.Modifier

import io.asterisque.test._
import io.asterisque.wire.Spec
import io.asterisque.wire.message.Message.Open
import org.specs2.specification.core.SpecStructure

class OpenSpec extends AbstractMessageSpec {
  override def is:SpecStructure = super.is append
    s2"""
Open should:
declare as final class. ${Modifier.isFinal(classOf[Open].getModifiers) must beTrue}
It should throw exception if service id is too long. $tooLongServiceId
have properties these are specified in constructor. $openProperties
It throws NullPointerException if service ID is null. ${Open(1, 8, null, 12, Array.empty) must throwA[NullPointerException]}
throw NullPointerException if data is null. ${Open(1, 8, "service", 12, null) must throwA[NullPointerException]}
"""

  private[this] def tooLongServiceId = {
    Open(0.toByte, randomASCII(478682, Spec.Std.MAX_SERVICE_ID_BYTES + 1), 0.toShort, Array.empty) must throwA[IllegalArgumentException]
  }

  private[this] def openProperties = {
    val params = randomByteArray(73840, 256)
    val o = Open(1, 8, "service", 12, params)
    (o.pipeId === 1) and (o.priority === 8) and
      (o.serviceId === "service") and (o.functionId === 12) and (o.params === params)
  }

  protected override def newMessages:Seq[Message] = Seq(
    Open(0.toShort, randomASCII(4893, 128), 0.toShort, randomByteArray(738729, 256))
  )

}
