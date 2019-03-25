package io.asterisque.utils

import javax.annotation.Nonnull

import scala.util.{Failure, Success, Try}

/**
  * `[major:UINT8][minor:UINT8][patch:UINT16]` で表される数値でバージョンを構築します。
  *
  * @param version バージョン
  */
case class Version(version:Int) extends AnyVal {
  override def toString:String = {
    val major = (version >>> 24) & 0xFF
    val minor = (version >>> 16) & 0xFF
    val patch = version & 0xFFFF
    s"$major.$minor.$patch"
  }
}

object Version {

  /**
    * [Semantic Versioning](https://semver.org/) に基づくバージョン表現文字列からインスタンスを構築します。
    *
    * @param version バージョン文字列
    * @return バージョン
    */
  def apply(@Nonnull version:String):Version = Version(Try {
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
  }.get)
}