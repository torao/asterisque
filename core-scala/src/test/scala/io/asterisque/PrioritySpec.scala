/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// PrioritySpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class PrioritySpec extends AsterisqueSpec { def is = s2"""
Priority should:
be private class. ${beStaticUtility(classOf[Priority])}
have maximum, minimum, normal constant value. $e0
transfer lower and upper priority. $e1
""""
  def e0 = {
    (Priority.Max === Byte.MaxValue) and (Priority.Min === Byte.MinValue) and (Priority.Normal === 0)
  }
  def e1 = {
    (Byte.MinValue to Byte.MaxValue).map{ i =>
      (Priority.upper(i.toByte) === (if(i == Priority.Max) Priority.Max else i + 1)) and
      (Priority.lower(i.toByte) === (if(i == Priority.Min) Priority.Min else i - 1))
    }.reduce { _ and _ }
  }
}
