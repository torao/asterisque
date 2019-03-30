package io.asterisque

import java.io._
import java.nio.file.Path

import io.asterisque.tools.Bash.Unwrapped

package object tools {

  /**
    * Bash シェル上でコマンドを実行するための文字列コンテキスト。e.g., `sh"openssl ...".exec(silent=true)`
    *
    * @param sc 文字列コンテキスト
    */
  implicit class _BashContext(val sc:StringContext) {
    def sh(args:Any*):Bash = {
      val cmd = (0 until math.max(args.length, sc.parts.length)).foldLeft(new StringBuilder()) { case (buf, i) =>
        if(i < sc.parts.length) {
          buf.append(sc.parts(i))
        }
        if(i < args.length) {
          args(i) match {
            case file:File => buf.append(Bash.escape(Bash.unixPath(file)))
            case file:Path => buf.append(Bash.escape(Bash.unixPath(file.toFile)))
            case Unwrapped(text) => buf.append(String.valueOf(text))
            case value => buf.append(Bash.escape(String.valueOf(value)))
          }
        }
        buf
      }.toString
      new Bash(cmd)
    }
  }

}
