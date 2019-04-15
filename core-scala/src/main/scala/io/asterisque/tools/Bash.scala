package io.asterisque.tools

import java.io.{File, InputStream, OutputStream, OutputStreamWriter}

import io.asterisque.utils.IO.{NullInputStream, NullOutputStream}
import org.slf4j.LoggerFactory

import scala.annotation.tailrec


class Bash private[tools](cmd:String) {
  private[this] val logger = LoggerFactory.getLogger("io.asterisque.tools.BASH")

  def exec(stdin:InputStream = NullInputStream, stdout:OutputStream = NullOutputStream, stderr:OutputStream = NullOutputStream, silent:Boolean = false):Int = {
    var result:Option[Int] = None
    try {
      val proc = Runtime.getRuntime.exec("bash")
      val io = Seq(
        new Reader(proc.getInputStream, (if(silent) Seq(stdout) else Seq(stdout, System.out)):_*),
        new Reader(proc.getErrorStream, (if(silent) Seq(stderr) else Seq(stderr, System.err)):_*),
        new Writer(stdin, proc.getOutputStream, (if(silent) Seq.empty else Seq(System.out)):_*))
      io.foreach(_.start())
      val out = new OutputStreamWriter(proc.getOutputStream)
      out.write(s"$cmd; exit $$?\n")
      out.flush()
      result = Some(proc.waitFor())
      io.foreach(_.join())
      result.get
    } finally {
      result match {
        case Some(rc) => logger.debug(s"$cmd => $rc")
        case None => logger.error(s"$cmd (no result)")
      }
    }
  }

  /**
    * 指定された入力ストリームから読み込んだデータを複数の出力ストリームに出力します。
    *
    * @param in     入力ストリーム
    * @param outs   出力ストリーム
    * @param buffer 読み込みに使用するバッファ
    * @param size   現在の読み込みサイズ
    * @return 入力ストリームから読み込んだサイズ
    */
  @tailrec
  private[this] def readAndWrites(in:InputStream, outs:Seq[OutputStream], buffer:Array[Byte] = new Array[Byte](1024), size:Long = 0):Long = {
    val len = in.read(buffer)
    if(len > 0) {
      outs.foreach { out =>
        (0 until len).foreach { i =>
          out.write(buffer(i))
          if(buffer(i) == '\n') {
            out.flush()
          }
        }
        out.flush()
      }
      readAndWrites(in, outs, buffer, size + len)
    } else size
  }

  private[this] class Reader(is:InputStream, oss:OutputStream*) extends Thread {
    override def run():Unit = readAndWrites(is, oss)
  }

  private[this] class Writer(stdin:InputStream, con:OutputStream, oss:OutputStream*) extends Thread {
    override def run():Unit = {
      readAndWrites(stdin, con +: oss)
      con.close()
    }
  }

  override def toString:String = cmd

}

object Bash {

  case class Unwrapped(text:String)

  /**
    * `sh"... ${Bash.unescape("-name foo")} ..."` のように使用して内挿表記上でエスケープされない文字列挿入を行います。
    *
    * @param text sh 内挿表記でそのまま使用する文字列
    * @return エスケープしない指示のオブジェクト
    */
  def raw(text:String):Unwrapped = Unwrapped(text)

  /**
    * 指定された文字列を bash 上で 1 つのパラメータとして認識できるようにエスケープします。
    *
    * @param text エスケープする文字列
    * @return エスケープした文字列
    */
  def escape(text:String):String = text.foldLeft(new StringBuilder("\'")) { case (buffer, ch) =>
    if(ch == '\'') {
      buffer.append("\'\"\'\"\'")
    } else {
      buffer.append(ch)
    }
    buffer
  }.append('\'').toString()

  /**
    * 指定されたファイルを Unix 形式のパスを示す文字列に変換します。返値はカレントディレクトリからの相対パスになります。
    *
    * @param file Unix 形式に変換するファイル
    * @return Unix 形式のパス
    */
  def unixPath(file:File):String = new File(".").getCanonicalFile.toURI.relativize(file.toURI).toString
}
