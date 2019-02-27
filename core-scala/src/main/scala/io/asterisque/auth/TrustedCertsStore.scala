package io.asterisque.auth

import java.io._
import java.nio.charset.StandardCharsets
import java.security.cert._
import java.util.concurrent.atomic.AtomicReference

import io.asterisque.utils.KeyValueStore
import javax.naming.ldap.LdapName
import org.apache.commons.codec.binary.Hex
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}
import io.asterisque.carillon._
import TrustedCertsStore.logger

private[auth] class TrustedCertsStore(auth:Authority, rootCertsDirectory:File, cache:KeyValueStore) {

  /**
    * ローカル環境にデプロイされている信頼済み CA 証明書のリスト。自己署名証明書である必要はない。
    */
  private[this] val trustAnchors = new AtomicReference[Set[TrustAnchor]](Set.empty)

  /**
    * KVS にキャッシュされている中間 CA 証明書。ロール別に保持している。
    */
  private[this] val roles = new AtomicReference[Map[String, Seq[X509Certificate]]](Map.empty)

  /**
    * 現時点で有効なルートおよび中間 CA 証明書を参照します。返値は CRL やブロック済み証明書に含まれていないことは
    * 検証されていません。
    *
    * @return CA 証明書
    */
  def getCACertificates:Seq[X509Certificate] = {
    val trusted = trustAnchors.get().toSeq.map(_.getTrustedCert)
    (trusted ++ roles.get().getOrElse(Authority.ROLE_CA, Seq.empty))
      .filter(cert => Try(cert.checkValidity()).isSuccess)
  }

  /**
    * 現時点で有効なロール限定証明書を参照します。返値は CRL やブロック済み証明書に含まれていないことは検証されて
    * いません。
    *
    * @return ロール証明書
    */
  def getRoleCertificates(role:String):Seq[X509Certificate] = roles.get().getOrElse(role.toLowerCase, Seq.empty)
    .filter(cert => Try(cert.checkValidity()).isSuccess)

  /**
    * この信頼済み証明書リストに指定された中間 CA 証明書チェーンをインストールします。
    *
    * @param from      証明書チェーンの入手元を識別する情報
    * @param certPaths インストールする証明書チェーン
    * @return 1つ以上の証明書がインストールされた場合 true
    */
  def install(from:String, certPaths:Seq[CertPath]):Boolean = {
    val installed = certPaths.map { certPath =>
      val key = certPath.getEncoded(Algorithms.CertificateChain.ENCODING)
      if(cache.get(key) == null) {
        Algorithms.CertificateChain.validate(trustAnchors.get, certPath) match {
          case _:Success[_] =>
            val value = from.getBytes(StandardCharsets.UTF_8)
            cache.put(key, value)
            val summary = s"[${certPath.fingerprint}] ${certPath.getCertificates.asScala.head.asInstanceOf[X509Certificate].getSubjectX500Principal.getName}"
            logger.info(s"new intermediate certificate chain was installed from: $from $summary")
            true
          case Failure(ex) =>
            val dump = Json.prettyPrint(certPath.toJSON)
            logger.warn(s"unacceptable intermediate certificate chain from: $from\n$dump", ex)
            false
        }
      } else {
        logger.debug(s"the intermediate certificate chain already exist: ${certPath.fingerprint}")
        false
      }
    }.reduceLeft(_ || _)
    if(installed) {
      reload()
    }
    installed
  }

  def reload():Unit = {
    logger.debug("loading trusted CA certificates")
    trustAnchors.set(
      TrustedCertsStore.loadCertificates(rootCertsDirectory).map { cert =>
        new TrustAnchor(cert, null)
      }.toSet)
    roles.set(loadCachedIntermediateCertificates())
  }

  def clear():Unit = {
    cache.toMap.foreach { case (key, _) => cache.delete(key) }
    reload()
  }

  /**
    * ローカルにキャッシュされている中間 CA 証明書をロードし、ロールごとに Map 化して返します。
    *
    * @return ローカルにキャッシュされている中間 CA 証明書
    */
  private[this] def loadCachedIntermediateCertificates():Map[String, Seq[X509Certificate]] = {
    cache.toMap.toSeq.flatMap { case (key, value) =>
      lazy val hexKey = Hex.encodeHexString(key)
      lazy val hexValue = Hex.encodeHexString(value)
      try {
        val certPath = Algorithms.CertificateChain.load(key)
        Algorithms.CertificateChain.validate(trustAnchors.get(), certPath) match {
          case _:Success[_] => Some(certPath)
          case Failure(ex) =>
            logger.warn(s"cached certificate-chain has been invalid\n${Json.prettyPrint(certPath.toJSON)}", ex)
            cache.delete(key)
            None
        }
      } catch {
        case ex:Exception =>
          logger.error(s"fail to load intermediate-certificate from internal cache: $hexKey=$hexValue", ex)
          None
      }
    }.flatMap { certPath =>
      val cert = certPath.getCertificates.asScala.head.asInstanceOf[X509Certificate]
      val dname = cert.getSubjectX500Principal.getName
      new LdapName(dname).getRdns.asScala.map(_.toAttributes).collectFirst {
        case attr if Option(attr.get("CN")).map(_.size()).getOrElse(0) > 1 =>
          attr.get("ou").getAll.asScala.map(x => String.valueOf(x).trim().toLowerCase)
      }.map { roles =>
        roles.collect {
          case role if role.nonEmpty =>
            logger.info(s"intermediate [$role] cert: [${cert.fingerprint}] ${cert.getSubjectX500Principal.getName}")
            (role, cert)
        }
      }.getOrElse(Seq.empty)
    }.groupBy(_._1).mapValues(_.map(_._2))
  }

}

private[auth] object TrustedCertsStore {
  private[TrustedCertsStore] val logger = LoggerFactory.getLogger(classOf[TrustedCertsStore])

  /**
    * 指定された証明書が期限内であることを確認します。
    *
    * @param cert 検証する証明書
    * @return 検証結果
    */
  def confirmValidityOf(cert:X509Certificate):Try[Unit] = Try(cert.checkValidity())

  /**
    * Load X.509 certificates from the specified directory. This method is not retrieve recursively on subdirectories.
    *
    * @param dir directory to load certificates
    * @return certificates
    */
  def loadCertificates(dir:File):Array[X509Certificate] = Option(dir.listFiles(f => f.isFile && f.getName.endsWith(".crt")))
    .getOrElse(Array.empty)
    .flatMap { file =>
      try {
        val cert = Algorithms.Certificate.load(file)
        confirmValidityOf(cert) match {
          case _:Success[_] =>
            logger.info(s"trust-anchor CA: [${cert.fingerprint}] ${cert.getSubjectX500Principal.getName}; ${file.getName}")
            Some(cert)
          case Failure(ex) =>
            logger.error(s"locally installed '$file' is an invalid trusted CA certificate\n${Json.prettyPrint(cert.toJSON)}", ex)
            None
        }
      } catch {
        case ex:Exception =>
          logger.error(s"locally installed '$file' is an invalid trusted CA certificate: $ex")
          None
      }
    }
}