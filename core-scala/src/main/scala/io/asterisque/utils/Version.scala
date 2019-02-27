package io.asterisque.utils

import scala.util.{Failure, Success, Try}

/**
  * [Semantic Versioning](https://semver.org/) に基づくバージョン表現クラスです。
  *
  * @param version バージョン文字列
  */
case class Version(version:String) {

  /**
    * このバージョンの数値表現を参照します。
    */
  val toInt:Int = Try {
    version.split("\\.", 4).toList.take(3).map(_.trim().toInt)
  }.recoverWith { case ex =>
    Failure(new IllegalArgumentException(s"illegal version format: $version", ex))
  }.flatMap {
    case major :: _ if major > 0xFF || major < 0 =>
      Failure(new IllegalArgumentException(s"major number is out-of-range: $version"))
    case _ :: minor :: _ if minor > 0xFF || minor < 0 =>
      Failure(new IllegalArgumentException(s"minor number is out-of-range: $version"))
    case _ :: _ :: patch :: _ if patch > 0xFFFF || patch < 0 =>
      Failure(new IllegalArgumentException(s"patch number is out-of-range: $version"))
    case major :: minor :: patch :: Nil =>
      Success(((major & 0xFF) << 24) | ((minor & 0xFF) << 16) | (patch & 0xFFFF))
  }.get

  override def toString:String = version
}

object Version {

  /**
    * 指定された数値表現のバージョンからインスタンスを構築します。
    *
    * @param version 数値表現のバージョン
    * @return バージョン
    */
  def apply(version:Int):Version = {
    val major = (version >> 24) & 0xFF
    val minor = (version >> 16) & 0xFF
    val patch = version & 0xFFFF
    Version(s"$major.$minor.$patch")
  }
}