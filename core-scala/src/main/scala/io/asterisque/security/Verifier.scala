package io.asterisque.security

import java.net.Socket
import java.security.cert.{CertPath, CertificateException, X509Certificate}

import io.asterisque.security.Verifier.logger
import io.asterisque.utils.Debug.{toString => D}
import javax.net.ssl.{SSLEngine, X509ExtendedTrustManager}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

private[security] class Verifier(trustedCAs:Seq[TrustedCA], blocked:Seq[X509Certificate]) {

  /**
    * 指定された証明書パスをこの Verifier に定義されている信用情報で検証します。
    *
    * @param certPath 検証する証明書パス
    * @throws IllegalArgumentException if null or zero-length array is passed
    *                                  in for the { @code chain} parameter or if null or zero-length
    *                                  string is passed in for the { @code authType} parameter
    * @throws CertificateException     if the certificate chain is not trusted
    *                                  by this TrustManager
    */
  @throws[IllegalArgumentException]
  @throws[CertificateException]
  def verify(certPath:Seq[X509Certificate]):Unit = {

    // 証明書パス単独での検証
    Algorithms.Cert.Path.verify(certPath)

    // 証明書パスの先頭にある認証対象の証明書が信頼済みであること
    check(certPath.head) match {
      case Some(Right(_)) => None
      case Some(Left(reason)) =>
        throw new CertificateException(reason)
      case None =>
        val name = certPath.head.getSubjectX500Principal.getName
        val cas = trustedCAs
          .map(_.certPath.getCertificates.asScala.head.asInstanceOf[X509Certificate].getSubjectDN.getName)
          .mkString("[", ", ", "]")
        throw new CertificateException(s"cert $name has not been issued by any of the trusted CAs: $cas")
    }

    // 発行者の CA がブロックされていないこと
    for(ca <- certPath.tail; reason <- checkBlocked(ca)) {
      throw new CertificateException(reason)
    }
  }

  /**
    * 指定された証明書パスをこの Verifier に定義されている信用情報で検証します。
    *
    * @param certPath 検証する証明書パス
    * @throws IllegalArgumentException if null or zero-length array is passed
    *                                  in for the { @code chain} parameter or if null or zero-length
    *                                  string is passed in for the { @code authType} parameter
    * @throws CertificateException     if the certificate chain is not trusted
    *                                  by this TrustManager
    */
  @throws[IllegalArgumentException]
  @throws[CertificateException]
  def verify(certPath:CertPath):Unit = verify(certPath.getCertificates.asScala.map(_.asInstanceOf[X509Certificate]))

  /**
    * 指定された証明書の信用状況を検査します。このメソッドは証明書がブロックされているかのみを検査するために使用することが
    * できます。
    *
    * @param cert 検査する証明書
    * @return 証明書が信頼できる場合 Some(Right(CA証明書))、ブロックされている場合 Some(Left(理由))、信頼済みCAによって
    *         発行されていない場合 None
    */
  private[this] def check(cert:X509Certificate):Option[Either[String, X509Certificate]] = {
    for(b <- blocked) {
      if(b == cert) {
        val name = cert.getSubjectX500Principal.getName
        return Some(Left(s"$name is blocked by local configuration"))
      }
    }
    for(ca <- trustedCAs) {
      ca.verify(cert) match {
        case None => None
        case result => return result
      }
    }
    None
  }

  /**
    * 指定された証明書がブロックされているかを検査します。
    *
    * @param cert 検査する証明書
    * @return ブロックされている場合、その理由のメッセージ
    */
  private[this] def checkBlocked(cert:X509Certificate):Option[String] = check(cert) match {
    case None => None
    case Some(Right(_)) => None
    case Some(Left(reason)) => Some(reason)
  }

  object TRUST_MANAGER extends X509ExtendedTrustManager {
    private[this] val acceptableIssuers = trustedCAs
      .map(_.certPath.getCertificates.asScala.head.asInstanceOf[X509Certificate])
      .filterNot(cert => checkBlocked(cert).isEmpty)


    override def checkClientTrusted(chain:Array[X509Certificate], authType:String, socket:Socket):Unit = {
      logger.debug(s"checkClientTrusted(${D(chain)}, ${D(authType)}, ${D(socket)})")
      verify(chain)
    }

    override def checkServerTrusted(chain:Array[X509Certificate], authType:String, socket:Socket):Unit = {
      logger.debug(s"checkServerTrusted(${D(chain)}, ${D(authType)}, ${D(socket)})")
      verify(chain)
    }

    override def checkClientTrusted(chain:Array[X509Certificate], authType:String, engine:SSLEngine):Unit = {
      logger.debug(s"checkClientTrusted(${D(chain)}, ${D(authType)}, ${D(engine)})")
      verify(chain)
    }

    override def checkServerTrusted(chain:Array[X509Certificate], authType:String, engine:SSLEngine):Unit = {
      logger.debug(s"checkServerTrusted(${D(chain)}, ${D(authType)}, ${D(engine)})")
      verify(chain)
    }

    override def checkClientTrusted(chain:Array[X509Certificate], authType:String):Unit = {
      logger.debug(s"checkClientTrusted(${D(chain)}, ${D(authType)})")
      verify(chain)
    }

    override def checkServerTrusted(chain:Array[X509Certificate], authType:String):Unit = {
      logger.debug(s"checkServerTrusted(${D(chain)}, ${D(authType)})")
      verify(chain)
    }

    override def getAcceptedIssuers:Array[X509Certificate] = acceptableIssuers.toArray
  }

}

private[security] object Verifier {
  private[Verifier] val logger = LoggerFactory.getLogger(classOf[Verifier])
}