/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.msg

import java.lang.reflect.Modifier

import org.specs2.Specification

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// ControlSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class ControlSpec extends Specification { def is = s2"""
Control should:
declare as final class. ${Modifier.isFinal(classOf[Control].getModifiers) must beTrue}
have properties these are specified in constructor. $e0
throw NullPointerException if data is null. ${new Control(Control.Close, null) must throwA[NullPointerException]}
"""

  def e0 = {
    val c1 = new Control(Control.SyncConfig)
    val c2 = new Control(Control.Close, new Array[Byte](256))
    (c1.code === Control.SyncConfig) and (c1.data.length === 0) and (c2.code === Control.Close) and (c2.data.length === 256)
  }

}
