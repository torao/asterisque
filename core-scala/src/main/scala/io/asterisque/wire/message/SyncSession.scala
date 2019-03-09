package io.asterisque.wire.message

import java.util.Objects

import io.asterisque.auth.Certificate
import io.asterisque.utils.Version
import io.asterisque.wire.message.ObjectMapper.CERTIFICATE
import io.asterisque.wire.{Envelope, Spec}
import javax.annotation.Nonnull


/**
  * 通信の開始時にピア間の設定を同期するための制御メッセージに付随するバイナリフィールドを参照するためのヘルパークラスです。
  * [[Message.Control#SyncSession]] の制御コードを持つ Control メッセージのデータに使用できます。
  *
  * @param version           プロトコルのバージョンを表す 2 バイト整数値。上位バイトから [major][minor] の順を持つ。[[Spec]] 参照。
  * @param sealedCertificate ノード証明書。通信が TLS を使用している場合はそちらの証明書と照合する必要がある。
  * @param serviceId         このセッションで処理要求の対象とするサービス名。
  * @param utcTime           UTC ミリ秒で表現した現在時刻。システム時刻確認の目的で使用される。
  * @param config            セッションのコンフィギュレーション
  * @author Takami Torao
  */
final case class SyncSession(@Nonnull version:Version, @Nonnull sealedCertificate:Envelope, @Nonnull serviceId:String, utcTime:Long, config:Map[String, String]) extends Message.Control.Fields {
  // TODO ping, sessionTimeout の入力値チェックとテストケース作成
  Objects.requireNonNull(version)
  Objects.requireNonNull(sealedCertificate)
  Objects.requireNonNull(serviceId)
  if(serviceId.getBytes(Spec.Std.charset).length > Spec.Std.maxServiceId) {
    throw new IllegalArgumentException(s"service id too long: $serviceId must be less than or equal ${Spec.Std.maxServiceId}")
  }

  /**
    * 署名済みの証明書を参照。
    */
  val cert:Certificate = CERTIFICATE.decode(sealedCertificate.payload)

  /**
    * 指定されたオブジェクトとこのインスタンスが等しい場合 true を返します。
    *
    * @param obj 比較するオブジェクト
    * @return 等しい場合 true
    */
  override def equals(obj:Any):Boolean = obj match {
    case other:SyncSession =>
      this.version == other.version &&
        this.sealedCertificate == other.sealedCertificate &&
        this.serviceId == other.serviceId &&
        this.utcTime == other.utcTime &&
        this.config == other.config
    case _ => false
  }

  /**
    * このインスタンスのハッシュ値を参照します。
    *
    * @return ハッシュ値
    */
  override def hashCode():Int = {
    Message.hashCode(version.version, sealedCertificate.hashCode(), serviceId.hashCode(), utcTime.toInt, config.hashCode())
  }
}

object SyncSession {

  /**
    * 現在のバージョンを使用して構築します。
    */
  def apply(@Nonnull sealedCertificate:Envelope, @Nonnull serviceId:String, utcTime:Long, attrs:Map[String, String]):SyncSession = {
    SyncSession(Spec.VERSION, sealedCertificate, serviceId, utcTime, attrs)
  }

  case class Pair(primary:SyncSession, secondary:SyncSession)

}
