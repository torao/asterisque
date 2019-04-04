package io.asterisque.wire.rpc

import org.specs2.Specification

class ExceptionSpec extends Specification {

  def is = s2"""
CodecException should be able to construct without error. $constructCodecException
Unsatisfied should be able to construct without error. $constructUnsatisfied
"""

  private[this] def constructCodecException = {
    new CodecException()
    new CodecException(new Exception())
    new CodecException("message")
    new CodecException("message", new Exception)
    new CodecException(null:String)
    new CodecException(null:Throwable)
    success
  }

  private[this] def constructUnsatisfied = {
    new Unsatisfied()
    new Unsatisfied(new Exception())
    new Unsatisfied("message")
    new Unsatisfied("message", new Exception)
    new Unsatisfied(null:String)
    new Unsatisfied(null:Throwable)
    success
  }

}
