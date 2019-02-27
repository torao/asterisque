package io.asterisque.carillon

import java.io._
import java.nio.file.Path

import org.slf4j.LoggerFactory

import scala.annotation.tailrec
;

package object tools {

  /**
    * Bash シェル上でコマンドを実行するための文字列コンテキスト。e.g., `bash"openssl ...".exec(silent=true)`
    *
    * @param sc 文字列コンテキスト
    */
  implicit class _BashContext(val sc:StringContext) {
    def sh(args:Any*):Bash = new Bash(sc.parts.zip(args :+ "").map {
      case (left, file:File) => left + unixPath(file)
      case (left, file:String) if new File(file).exists() => left + unixPath(new File(file))
      case (left, file:Path) => left + unixPath(file.toFile)
      case (left, value) => left + String.valueOf(value)
    }.mkString)

    private[this] def unixPath(file:File):String = new File(".").getCanonicalFile.toURI.relativize(file.toURI).toString

  }

  class Bash private[tools](cmd:String) {
    private[this] val logger = LoggerFactory.getLogger("io.asterisque.carillon.tools.BASH")

    def exec(input:String = "", stdout:OutputStream = System.out, silent:Boolean = false):Int = {
      var result:Option[Int] = None
      try {
        val proc = Runtime.getRuntime.exec("bash")
        val in = Seq(
          new Reader(proc.getInputStream, stdout, silent),
          new Reader(proc.getErrorStream, System.err, silent),
          new Writer(input, proc.getOutputStream, System.out, silent))
        in.foreach(_.start())
        val out = new OutputStreamWriter(proc.getOutputStream)
        out.write(s"$cmd; exit $$?\n")
        out.flush()
        result = Some(proc.waitFor())
        in.foreach(_.join())
        result.get
      } finally {
        result match {
          case Some(rc) => logger.debug(s"$cmd => $rc")
          case None => logger.error(s"$cmd")
        }
      }
    }

    private[this] class Reader(is:InputStream, os:OutputStream, silent:Boolean) extends Thread {
      private[this] val in = new InputStreamReader(is)
      private[this] val out = new OutputStreamWriter(os)

      override def run():Unit = {
        @tailrec
        def _read(buffer:Array[Char] = new Array[Char](1024)):Unit = {
          val len = in.read(buffer)
          if(len > 0) {
            if(!silent) {
              out.write(buffer, 0, len)
              out.flush()
            }
            _read(buffer)
          }
        }

        _read()
      }

    }

    private[this] class Writer(input:String, os:OutputStream, c:OutputStream, silent:Boolean) extends Thread {
      private[this] val out = new PrintWriter(new OutputStreamWriter(os))
      private[this] val con = new PrintWriter(new OutputStreamWriter(c))

      override def run():Unit = if(input.nonEmpty) {
        input.split("\r?\n").foreach { line =>
          out.println(line)
          con.println(line)
          out.flush()
          con.flush()
        }
      }

    }

    override def toString:String = cmd

  }

}
