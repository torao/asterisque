package io.asterisque.wire.message

import java.lang.reflect.Modifier

import io.asterisque.utils.Version
import org.specs2.Specification
import org.specs2.specification.core.SpecStructure

class SyncSessionSpec extends Specification {
  def is:SpecStructure =
    s2"""
It should be declared as final class. ${Modifier.isFinal(classOf[SyncSession].getModifiers)}
SyncSession constracts instance without wsCaughtException. $allConstructorsTest
"""

  private[this] def allConstructorsTest = {
    SyncSession(Version(0), 0, Map.empty)
    SyncSession(0, Map.empty)
    success
  }

}
