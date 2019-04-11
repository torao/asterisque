package io.asterisque.utils

import java.io.{DataOutputStream, File}
import java.security.{DigestOutputStream, MessageDigest}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

import io.asterisque.utils.Cache.{Transformer, logger}
import org.slf4j.LoggerFactory

import scala.collection.mutable

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

  /**
    * このキャッシュが保持しているオブジェクトをリセットし、次回の参照時に確実に更新されるようにします。
    *
    * @param file リセットするオブジェクトのファイル
    * @return 現在キャッシュされているオブジェクト
    */
  def reset(file:File):T = {
    val key = file.getCanonicalPath
    Option(cache.remove(key)).map(_.value).getOrElse(transformer.defaultValue(file))
  }

  private[this] class Entry(val file:File) {
    val lastVerifiedAt:AtomicLong = new AtomicLong(Long.MinValue)
    var identity:Long = Long.MaxValue
    var value:T = transformer.defaultValue(file)

    /**
      * キャッシュエントリのファイルが更新されているかを確認し、更新されている場合にオブジェクトを再生成します。
      *
      * @return オブジェクトを更新したエントリ
      */
    def update():Entry = {
      val tm = System.currentTimeMillis()
      val verifiedAt = this.lastVerifiedAt.get()
      if(tm > verifiedAt + periodToOmitTimestampVerification && this.lastVerifiedAt.compareAndSet(verifiedAt, tm)) {
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
    def defaultValue(target:File):T

    def transform(target:File):T

    /**
      * 指定されたターゲットの更新を検知するためのハッシュ値を参照します。例えばタイムスタンプをハッシュ値とする場合、タイム
      * スタンプの更新によってファイルが変更されたものと認識されます。
      *
      * @param target 対象のファイルまたはディレクトリ
      * @return 対象のハッシュ値
      */
    def hash(target:File):Long
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
    * ディレクトリに存在するファイルからオブジェクトを生成するトランスフォーマーです。
    *
    * @param filter  ファイルのフィルタ
    * @param recurse 直下のディレクトリより下を再帰的に検索する場合 true
    * @tparam T このトランスフォーマーが生成するオブジェクトの型
    */
  abstract class DirTransformer[T](filter:File => Boolean = _ => true, recurse:Boolean = true) extends Transformer[T] {
    def transform(dir:File):T = transform(listFiles(dir))

    override def hash(dir:File):Long = fileHashes(listFiles(dir).sortBy(_.getCanonicalPath))

    def transform(files:Seq[File]):T

    /**
      * 処理対象のファイルを参照します。このメソッドをオーバーライドして特定の拡張子を持つファイルのみを対象とすることが
      * できます。
      *
      * @param dir ディレクトリ
      * @return 処理対象のファイル
      */
    protected def listFiles(dir:File):Seq[File] = {
      def list(dir:File, files:mutable.Buffer[File] = mutable.Buffer()):Seq[File] = {
        Option(dir.listFiles()).getOrElse(Array.empty).foreach { file =>
          if(file.isFile && filter(file)) {
            files.append(file)
          } else if(file.isDirectory && recurse) {
            list(file, files)
          }
        }
        files
      }

      list(dir)
    }
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