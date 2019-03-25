package io.asterisque.wire.message

import java.lang.reflect.Modifier

import io.asterisque.wire.message.Message.Close
import io.asterisque.wire.message.Message.Close.Code
import org.specs2.execute.Result
import org.specs2.specification.core.SpecStructure

class CloseSpec extends AbstractMessageSpec {
  override def is:SpecStructure = super.is append
    s2"""
Close should:
declare as final. ${Modifier.isFinal(classOf[Close].getModifiers) must beTrue}
have properties that specified in constructor. $closeProperties
create unexpected error. $closeError
It raise exception if failure result with success code. $errorWithSuccessCode
"""

  private[this] def closeProperties = {
    val result = "hoge".getBytes
    val c0 = Close(1.toShort, result)
    val c1 = Close.withFailure(2.toShort, 30.toByte, "foo")
    (c0.pipeId === 1) and (c0.toEither match {
      case Right(x) if x sameElements result => success
      case _ => failure
    }) and (c1.pipeId === 2) and (c1.toEither match {
      case Left((code, message)) => (code === 30) and (message === "foo")
      case _ => failure
    })
  }

  private[this] def closeError = {
    val c = Close.withFailure(1, "error")
    (c.pipeId === 1) and ((c.toEither match {
      case Left((code, msg)) => (code === Close.Code.UNEXPECTED) and (msg === "error")
      case _ => failure
    }):Result)
  }

  private[this] def errorWithSuccessCode = {
    Close.withFailure(0, Code.SUCCESS, "NG") must throwA[IllegalArgumentException]
  }

  protected override def newMessages:Seq[Message] = Seq(
    Close(0.toShort, "foo".getBytes),
    Close.withFailure(1, "bar")
  )

}
