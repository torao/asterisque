package io.asterisque

import java.io.{File, FileOutputStream, InputStream, OutputStream}

import org.slf4j.LoggerFactory

import scala.annotation.tailrec

package object utils {

  /**
    * 指定された処理の終了時にリソースを開放します。
    *
    * @param resource 終了時に開放するリソース
    * @param f        処理
    * @tparam R リソースの型
    * @tparam S 処理結果の型
    * @return 処理結果
    */
  def using[R <: AutoCloseable, S](resource:R)(f:R => S):S = try {
    f(resource)
  } finally {
    if(resource != null) {
      resource.close()
    }
  }

  object IO {
    private[this] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))

    def touch(file:File):Unit = new FileOutputStream(file).close()

    /**
      * 指定されたディレクトリを再帰的に削除します。
      *
      * @param dir 削除するディレクトリ
      */
    def rmdirs(dir:File):Unit = {
      def _rmdirs(dir:File):Unit = Option(dir.listFiles()).getOrElse(Array.empty).foreach {
        case file if file.isFile =>
          if(!file.delete()) {
            logger.warn(s"fail to delete file: $file")
          }
        case subdir if subdir.isDirectory =>
          _rmdirs(subdir)
      }

      _rmdirs(dir)
      if(!dir.delete()) {
        logger.warn(s"fail to delete directory: $dir")
      }
    }

    /**
      * 指定された入力ストリームから読みだされるバイナリデータを出力ストリームに出力します。
      *
      * @param in         入力ストリーム
      * @param out        出力ストリーム
      * @param bufferSize 使用するバッファサイズ
      * @return コピーしたバイト数
      */
    def copy(in:InputStream, out:OutputStream, bufferSize:Int = 1024):Long = {
      @tailrec
      def _copy(in:InputStream, out:OutputStream, buffer:Array[Byte], size:Long):Long = {
        val len = in.read(buffer)
        if(len < 0) size else {
          out.write(buffer, 0, len)
          _copy(in, out, buffer, size + len)
        }
      }

      _copy(in, out, new Array[Byte](bufferSize), 0L)
    }

    /**
      * スコープを限定してテンポラリファイルを作成します。
      *
      * @param dir            テンポラリファイルを保存するディレクトリ
      * @param prefix         ファイルの接頭辞
      * @param suffix         ファイルの接尾辞
      * @param leaveIfFailure 処理が失敗したときにテンポラリファイルを残す場合 true (デフォルト: true)
      * @param f              テンポラリファイルを使用する処理
      * @tparam T 処理結果の型
      * @return 処理結果
      */
    def temp[T](dir:File, prefix:String = "$$$", suffix:String = ".tmp", leaveIfFailure:Boolean = true)(f:File => T):T = {
      val file = File.createTempFile(prefix, suffix, dir)
      var success = false
      try {
        val result = f(file)
        success = true
        result
      } finally {
        if((success || !leaveIfFailure) && file.isFile) {
          if(!file.delete()) {
            logger.warn(s"fail to delete temporary file:  $file")
          }
        }
      }
    }
  }

}
