package io.asterisque.wire.message

import java.lang.reflect.Modifier

import io.asterisque.auth.Certificate
import io.asterisque.test.NODE_CERTS
import io.asterisque.utils.Version
import io.asterisque.wire.Envelope
import io.asterisque.wire.message.Message.Control

import scala.util.Random

class ControlSpec extends AbstractMessageSpec {
  override def is = super.is ^
    s2"""
It should declare as final class. ${Modifier.isFinal(classOf[Control].getModifiers) must beTrue}
It has properties these are specified in constructor. $verifyConstructorParameter
throw NullPointerException if data is null. ${new Control(null) must throwA[NullPointerException]}
The pipe-id must be zero. ${Control.CloseMessage.pipeId === 0}
"""

  private[this] def verifyConstructorParameter = {
    val c1 = Control(Control.CloseField)
    c1.data === Control.CloseField
  }

  protected def newMessages:Seq[Control] = {
    val r = new Random(78287435L)
    val version = Version(r.nextInt().toShort)
    val (privateKey, cert) = NODE_CERTS.head
    val attr = Map("role" -> "ca,miner", "address" -> "Tokyo")
    val envelope = Envelope.seal(ObjectMapper.CERTIFICATE.encode(Certificate(cert, attr)), cert, privateKey)
    val serviceId = "io.asterisque.TestService"
    val utcTime = r.nextLong()
    val ping = r.nextInt()
    val sessionTimeout = r.nextInt()
    val config = Map("ping" -> ping.toString, "sessionTimeout" -> sessionTimeout.toString)
    val syncSession = SyncSession(version, envelope, serviceId, utcTime, config)

    Seq(
      new Control(syncSession),
      Control.CloseMessage
    )
  }

}
