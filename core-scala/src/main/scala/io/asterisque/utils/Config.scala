package io.asterisque.utils

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

import com.typesafe.config.{ConfigException, ConfigFactory, ConfigList, Config => TConfig}
import io.asterisque.utils.Config._
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.{Success, Try}

class Config private[Config](config:TConfig, namespace:String = "") {

  /**
    * デバッグログ出力済みの設定値を保存するための並列化マップ (Set 版が存在しないため)。
    */
  private[this] val reported = new ConcurrentHashMap[String, AtomicBoolean]()

  @throws[NoSuchElementException]
  def apply(key:String):String = getAs(key, config.getString, identity[String], throw new NoSuchElementException(key))

  def get(key:String):Option[String] = getAs(key, config.getString, { value:String => Some(value) }, None)

  def getInt(key:String):Option[Int] = getAs(key, config.getInt, { value:Int => Some(value) }, None)

  def getOrElse(key:String, default: => String):String = getAs(key, config.getString, identity[String], default)

  def getOrElseInt(key:String, default: => Int):Int = getAs(key, config.getInt, identity[Int], default)

  def getOrElseLong(key:String, default: => Long):Long = getAs(key, config.getLong, identity[Long], default)

  def getOrElseBoolean(key:String, default: => Boolean):Boolean = getAs(key, config.getBoolean, identity[Boolean], default)

  def getList(key:String):Iterable[AnyRef] = getAs(key, config.getList, { c:ConfigList =>
    c.unwrapped().asScala
  }, List.empty)

  def getMap(key:String):Map[String, AnyRef] = getAs(key, config.getConfig, { c:TConfig =>
    c.entrySet().asScala.map(e => (e.getKey, e.getValue.unwrapped())).toMap
  }, Map.empty)

  /**
    * この設定から指定された名前空間のサブセットを作成します。
    *
    * @param namespace サブセット設定の名前空間
    * @return サブセット設定
    */
  def getConfig(namespace:String):Config = new Config(config.getConfig(namespace), s"${this.namespace}$namespace.")

  /**
    * 指定されたキーに対応する設定値を参照します。
    *
    * @param key       参照する設定値のキー
    * @param generator 設定値を参照する関数
    * @param transform 設定値としての値を目的の型に変換する関数
    * @param default   値が設定されていなかった場合に適用する関数
    * @tparam T 設定値の型
    * @tparam U 元の設定値の型
    * @return キーに対応する設定値
    */
  private[this] def getAs[T, U](key:String, generator:String => U, transform:U => T, default: => T):T = {

    // 設定から値を参照して目的の型に変換する
    val (value, message) = Try(generator(key)).flatMap { rawValue =>
      Try(transform(rawValue))
        .map(value => (Success(value):Try[T], ""))
        .recover { case ex => (Try(default), s"${Debug.toString(rawValue)} => $ex") }
    }.recover {
      case ex:ConfigException.Missing =>
        (Try(default), ex.getClass.getSimpleName)
      case ex =>
        val actualValue = Try(Debug.toString(config.getValue(key).unwrapped())).getOrElse("---")
        (Try(default), s"${ex.getClass.getSimpleName}: $actualValue; ${ex.getMessage}")
    }.get

    // キーに対してログ出力を行っていなければログ出力を行う
    reportAndGet(key, value, message)
  }

  /**
    * キーに対する値をログ出力します。このメソッドは二度目以降の冗長な出力を行わないために以前に出力したキーを保持します。
    *
    * @param key     キー
    * @param value   キーに対する設定値、またはエラー
    * @param message 補助的なメッセージ (長さ 0 の文字列は出力しない)
    * @tparam T 設定値の型
    * @return キーに対する設定値
    */
  private[this] def reportAndGet[T](key:String, value:Try[T], message:String):T = {
    val logValue = value.map(Debug.toString).recover { case ex => ex.toString }.get
    val set = reported.computeIfAbsent(s"$key=$logValue", _ => new AtomicBoolean(false))
    if(set.compareAndSet(false, true)) {
      val logValue = value.map(Debug.toString).recover { case ex => ex.toString }.get
      val name = s"${if(namespace.nonEmpty) s"{$namespace}" else ""}$key"
      val suffix = if(message.isEmpty) "" else s" ($message)"
      logger.debug(f"$name%-25s := $logValue$suffix")
    }
    value.get
  }

}

object Config {
  private[Config] val logger = LoggerFactory.getLogger(classOf[Config])

  /**
    * 1 つ以上の設定ファイルから設定を構築します。同一のキーが存在する場合は後ろの設定で上書きされます。
    *
    * @param first 1 つ目のファイル
    * @param files 残りのファイル
    * @return 設定
    */
  def apply(first:File, files:File*):Config = {
    val config = files.foldLeft(ConfigFactory.parseFile(first)) { case (cnf, file) =>
      logger.info(s"loading configuration: ${file.getCanonicalPath}")
      ConfigFactory.parseFile(file).withFallback(cnf)
    }
    new Config(config)
  }

  def getDefault:Config = new Config(ConfigFactory.defaultApplication())

}