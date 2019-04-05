package io.asterisque.security

import java.io.File
import java.security.cert.{CertPath, X509CRL, X509Certificate}
import java.text.SimpleDateFormat
import java.util.{Locale, TimeZone}

import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.Try

/**
  * 信頼済み CA を表すクラスです。ルート CA からの証明書パスと、この CA によって発行された CRL を持ち、任意の証明書が信用
  * できるかを検証します。
  *
  */
private[security] case class TrustedCA(certPath:CertPath, crls:Seq[X509CRL]) {
  private[this] val targetCA = certPath.getCertificates.asScala.head.asInstanceOf[X509Certificate]

  /**
    * この信頼済み CA 証明書の有効期限を参照します。
    */
  val expiredAt:Long = targetCA.getNotBefore.getTime

  /**
    * 指定された証明書がこの信頼済み CA によって発行されたこと (そして取り消されていないこと) を検証します。
    *
    * @param cert 検証する証明書
    * @return この CA が発行した有効な証明書である場合 Some(Right(CA証明書))、この CA が発行したが無効な証明書の場合
    *         Some(Left(理由))、この CA が発行した証明書ではない場合 None
    */
  def verify(cert:X509Certificate):Option[Either[String, X509Certificate]] = {
    assert(Try(cert.checkValidity()).isSuccess)
    if(Try {
      targetCA.checkValidity()
      cert.verify(targetCA.getPublicKey)
    }.isSuccess) {
      val entries = crls.flatMap(crl => Option(crl.getRevokedCertificate(cert)))
      if(entries.isEmpty) {
        Some(Right(targetCA))
      } else {
        val df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
        df.setTimeZone(TimeZone.getTimeZone("UTC"))
        val subject = cert.getSubjectX500Principal.getName
        val issuer = targetCA.getSubjectX500Principal.getName()
        val reason = Option(entries.head.getRevocationReason).map(_.toString).getOrElse("NOT_SPECIFIED")
        val date = Option(entries.head.getRevocationDate).map(dt => df.format(dt)).getOrElse("---")
        val serial = entries.head.getSerialNumber
        Some(Left(s"certificate $subject revoked by $issuer (date=$date, serial=$serial, reason=$reason)"))
      }
    } else None
  }

}

private[security] object TrustedCA {
  private[TrustedCA] val logger = LoggerFactory.getLogger(classOf[TrustedCA])

  /**
    * @param file PEM または PKCS#7 形式のファイル
    */
  def apply(file:File):Option[TrustedCA] = {
    Algorithms.Cert.Path.load(file).map { certPath =>
      val crls = Algorithms.CRL.loads(file)
      TrustedCA(certPath, crls)
    }
  }
}