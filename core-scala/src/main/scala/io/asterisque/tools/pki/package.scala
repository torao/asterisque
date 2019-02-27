package io.asterisque.tools

import io.asterisque.carillon.tools._

package object pki {

  private[this] val cmd = "openssl"

  object openssl {
    def apply(param:Bash, silent:Boolean = false):Unit = this.synchronized {
      val result = sh"""$cmd ${param.toString}""".exec(silent = silent)
      if(result != 0) {
        throw new IllegalStateException(s"$cmd exit with $result")
      }
    }
  }

}
