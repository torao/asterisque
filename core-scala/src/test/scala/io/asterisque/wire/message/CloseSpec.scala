package io.asterisque.wire.message

import java.lang.reflect.Modifier

import io.asterisque.wire.message.Message.Close
import org.specs2.execute.Result
import org.specs2.specification.core.SpecStructure

import scala.util.{Failure, Success}

class CloseSpec extends AbstractMessageSpec {
  override def is:SpecStructure = super.is append
    s2"""
Close should:
declare as final. ${Modifier.isFinal(classOf[Close].getModifiers) must beTrue}
have properties that specified in constructor. $closeProperties
create unexpected error. $closeError
"""

  private[this] def closeProperties = {
    val result = "hoge".getBytes
    val c0 = Close(1.toShort, Success(result))
    val c1 = Close(2.toShort, Failure(Abort(300, "foo")))
    (c0.pipeId === 1) and (c0.result match {
      case Success(x) if x sameElements result => success
      case _ => failure
    }) and (c1.pipeId === 2) and (c1.result match {
      case Failure(ex:Abort) => (ex.code === 300) and (ex.message === "foo")
      case _ => failure
    })
  }

  private[this] def closeError = {
    val c = Close(1, Failure(Abort("error")))
    (c.pipeId === 1) and ((c.result match {
      case Failure(Abort(code, msg)) => (code === Abort.Unexpected) and (msg === "error")
      case _ => failure
    }):Result)
  }

  protected override def newMessages:Seq[Message] = Seq(
    Close(0.toShort, Success("foo".getBytes)),
    Close(1, Failure(new Exception("bar")))
  )

}
