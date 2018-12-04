package io.asterisque

class PrioritySpec extends AsterisqueSpec {
  def is =
    s2"""
Priority should:
be private class. ${beStaticUtility(classOf[Priority])}
have maximum, minimum, normal constant value. $e0
transfer lower and upper priority. $e1
""""

  private[this] def e0 = {
    (Priority.Max === Byte.MaxValue) and (Priority.Min === Byte.MinValue) and (Priority.Normal === 0)
  }

  private[this] def e1 = {
    (Byte.MinValue to Byte.MaxValue).map { i =>
      (Priority.upper(i.toByte) === (if(i == Priority.Max) Priority.Max else i + 1)) and
        (Priority.lower(i.toByte) === (if(i == Priority.Min) Priority.Min else i - 1))
    }.reduce(_ and _)
  }
}
