package io.asterisque.utils

import java.io.{DataOutputStream, File}
import java.security.{DigestOutputStream, MessageDigest}
import java.util.concurrent.ConcurrentHashMap

import io.asterisque.utils.Cache.{Transformer, logger}
import org.slf4j.LoggerFactory

/**
  * ファイルまたはディレクトリから生成したオブジェクトをメモリ上に保持するキャッシュです。
  *
  * @param transformer                       ファイルまたはディレクトリからオブジェクトを作成する関数
  * @param periodToOmitTimestampVerification 更新タイムスタンプのチェックを行う間隔
  * @tparam T このキャッシュが保持するオブジェクトの型
  */
class Cache[T](transformer:Transformer[T], periodToOmitTimestampVerification:Long = 2 * 1000L) {

  /**
    * このインスタンスが保持しているキャッシュ。
    */
  private[this] val cache = new ConcurrentHashMap[String, Entry]()

  /**
    * このキャッシュから指定されたファイルのオブジェクトを参照します。まだオブジェクトが生成されていない場合や、オブジェクトを
    * 生成した後にファイルが更新されている場合はオブジェクトが再構築されます。
    *
    * オブジェクトの生成時に例外が発生した場合はコンストラクタで指定されたデフォルト値を返します。
    *
    * @param file        参照するオブジェクトのファイルまたはディレクトリ
    * @param forceUpdate 強制的にオブジェクトを再構築する場合 true
    * @return ファイルに対して生成されたオブジェクト
    */
  def get(file:File, forceUpdate:Boolean = false):T = {
    val key = file.getCanonicalPath
    val entry = cache.compute(key, { (_, oldEntry) =>
      (if(oldEntry == null || forceUpdate) new Entry(file) else oldEntry).update()
    })
    entry.value
  }

  private[this] class Entry(val file:File) {
    var identity:Long = Long.MinValue
    var lastVerifiedAt:Long = Long.MinValue
    var value:T = _

    /**
      * キャッシュエントリのファイルが更新されているかを確認し、更新されている場合にオブジェクトを再生成します。
      *
      * @return オブジェクトを更新したエントリ
      */
    def update():Entry = {
      val tm = System.currentTimeMillis()
      if(tm > this.lastVerifiedAt + periodToOmitTimestampVerification) {
        this.lastVerifiedAt = tm
        val identity = transformer.hash(this.file)
        if(this.identity != identity) {
          this.identity = identity
          this.value = try {
            val value = transformer.transform(this.file)
            logger.info(s"cache was loaded from: $file => ${Debug.toString(value)}")
            value
          } catch {
            case ex:Exception =>
              val defaultValue = transformer.defaultValue(this.file)
              Cache.logger.warn(s"cache transformation error: ${this.file}; apply default value: ${Debug.toString(defaultValue)}", ex)
              defaultValue
          }
        }
      }
      this
    }
  }

}

object Cache {
  private[Cache] val logger = LoggerFactory.getLogger(classOf[Cache[_]])

  trait Transformer[T] {
    def defaultValue(file:File):T

    def transform(file:File):T

    def hash(file:File):Long
  }

  /**
    * 単一のファイルからオブジェクトを生成するトランスフォーマーです。
    *
    * @tparam T このトランスフォーマーが生成するオブジェクトの型
    */
  trait FileTransformer[T] extends Transformer[T] {
    override def hash(file:File):Long = fileHashes(Option(file))
  }

  /**
    * ディレクトリ直下に存在するファイルからオブジェクトを生成するトランスフォーマーです。
    *
    * @tparam T このトランスフォーマーが生成するオブジェクトの型
    */
  trait DirTransformer[T] extends Transformer[T] {
    def transform(dir:File):T = transform(listFiles(dir))

    override def hash(dir:File):Long = fileHashes(listFiles(dir).sortBy(_.getName))

    def transform(files:Seq[File]):T

    /**
      * 処理対象のファイルを参照します。このメソッドをオーバーライドして特定の拡張子を持つファイルのみを対象とすることが
      * できます。
      *
      * @param dir ディレクトリ
      * @return 処理対象のファイル
      */
    protected def listFiles(dir:File):Seq[File] = Option(dir.listFiles())
      .getOrElse(Array.empty).filter(_.isFile).toSeq
  }

  /**
    * 指定されたファイルの更新検出のためのハッシュ値を参照します。
    *
    * @param files ハッシュ値を参照するファイル
    * @return ファイルのハッシュ値
    */
  private[this] def fileHashes(files:Iterable[File]):Long = {
    val md = MessageDigest.getInstance("SHA-512/256")
    val os = new DigestOutputStream(IO.NullOutputStream, md)
    val out = new DataOutputStream(os)
    files.foreach { file =>
      out.writeLong(file.lastModified())
      out.writeLong(file.length())
    }
    out.flush()
    BigInt(md.digest()).toLong
  }

}