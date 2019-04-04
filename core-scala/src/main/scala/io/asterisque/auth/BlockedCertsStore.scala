package io.asterisque.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.{X509CRL, X509Certificate}
import java.util.concurrent.atomic.AtomicReference

import io.asterisque.auth.Authority.ValidityException
import io.asterisque.carillon._
import io.asterisque.utils.KeyValueStore
import org.apache.commons.codec.binary.Hex
import org.slf4j.LoggerFactory
import play.api.libs.json.Json

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

import BlockedCertsStore._

/**
  * ブロックリストや CRL によって無効化されている証明書を判定するクラス。
  *
  * @param cache インストールされた CRL を保存するキャッシュ
  */
private[auth] class BlockedCertsStore(auth:Authority, cache:KeyValueStore) {

  /**
    * ブロックされている証明書のハッシュ。
    */
  private[this] val hashes = new AtomicReference[Set[BigInt]](Set.empty)

  /**
    * 指定された証明書がブロックされている証明書リストに含まれているかを判定します。
    *
    * @param cert 判定する証明書
    * @return ブロックされている場合 true
    */
  def isBlocking(cert:X509Certificate):Boolean = {
    val hashes = this.hashes.get()
    if(hashes.isEmpty) false else hashes.contains(getHash(cert))
  }

  /**
    * このブロック済み証明書リストのキャッシュに指定された CRL をインストールします。
    *
    * @param from CRL の入手元を識別する情報
    * @param crls インストールする CRL
    * @return 1つ以上の CRL がインストールされた場合 true
    */
  def install(from:String, crls:Seq[X509CRL]):Boolean = {
    var installed = false
    crls.foreach { crl =>
      val dump = Json.prettyPrint(crl.toJSON)
      acceptable(crl) match {
        case _:Success[_] =>
          if(cache.get(crl.getEncoded) == null) {
            cache.put(crl.getEncoded, from.getBytes(StandardCharsets.UTF_8))
            logger.info(s"new CRL is installed to local cache from: $from\n$dump")
            installed = true
          } else {
            logger.debug(s"the specified CRL is already exist: ${crl.fingerprint}")
          }
        case Failure(ex) =>
          logger.warn(s"unacceptable CRL from: $from\n$dump", ex)
      }
    }
    if(installed) {
      reload()
    }
    installed
  }

  def reload():Unit = {
    hashes.set(loadCRLs())
  }

  def clear():Unit = {
    cache.toMap.foreach { case (key, _) => cache.delete(key) }
    reload()
  }

  /**
    * 指定された CRL が有効であることを検証します。
    * CRL に有効期限の切れた証明書が含まれていていること自体は問題でないことに注意。
    *
    * @param crl 検証する CRL
    * @return 検証結果
    */
  private[this] def acceptable(crl:X509CRL):Try[Unit] = {
    val certs = crl.getRevokedCertificates.asScala.map(_.asInstanceOf[X509Certificate])

    if(certs.isEmpty) {
      return Failure(new ValidityException(s"empty CRL\n${Json.prettyPrint(crl.toJSON)}"))
    }

    // 証明書を所有する本人によって発行された失効証明書リスト
    if(certs.size == 1 && Try(crl.verify(certs.head.getPublicKey)).isSuccess && auth.findIssuer(certs.head).isDefined) {
      logger.debug(s"confirmed that the CRL was issued by the certificate owner himself: ${Json.prettyPrint(certs.head.toJSON)}")
      return Success(())
    }

    // CRL 発行者が CA であり、失効証明書が全てその CA によって発行されている
    auth.getCACertificates.find(ca => Try(crl.verify(ca.getPublicKey)).isSuccess) match {
      case Some(issuer) =>
        val problems = certs.map(cert => Try(cert.verify(issuer.getPublicKey))).collect { case Failure(ex) => ex }
        if(problems.isEmpty) {
          logger.debug(s"confirmed that the CRL was issued by a trusted CA: ${issuer.fingerprint}")
          Success(())
        } else {
          Failure(new ValidityException(s"the CRL contains certificates that have not been issued by the trusted CA that issued the CRL\n${Json.prettyPrint(crl.toJSON)}", problems.toSeq:_*))
        }
      case None =>
        Failure(new ValidityException(s"issuer of the CRL is not a trusted CA\n${Json.prettyPrint(crl.toJSON)}"))
    }
  }

  /**
    * キャッシュに保存されている CRL をロードしてブロック済み証明書のハッシュ Set を作成します。
    *
    * @return
    */
  private[this] def loadCRLs():Set[BigInt] = {
    val hashes = mutable.HashSet[BigInt]()
    cache.toMap.toSeq.foreach { case (key, value) =>
      lazy val hexKey = Hex.encodeHexString(key)
      lazy val hexValue = Hex.encodeHexString(value)
      try {
        val crl = Algorithms.CRL.loads(key).head
        acceptable(crl) match {
          case _:Success[_] =>
            crl.getRevokedCertificates.asScala.map(_.asInstanceOf[X509Certificate]).collect {
              case cert if TrustedCertsStore.confirmValidityOf(cert).isSuccess =>
                getHash(cert)
            }
          case Failure(ex) =>
            logger.error(s"fail to load CRL from internal cache: $hexKey=$hexValue", ex)
            cache.delete(key)
        }
      } catch {
        case ex:Exception =>
          logger.error(s"fail to load CRL from internal cache: $hexKey=$hexValue", ex)
          cache.delete(key)
      }
    }
    hashes.toSet
  }

}

private[auth] object BlockedCertsStore {
  private[BlockedCertsStore] val logger = LoggerFactory.getLogger(classOf[BlockedCertsStore])
  private[this] val HashAlgorithm = "SHA-256"

  /**
    * 指定された証明書からハッシュ値を算出します。
    *
    * @param cert 証明書
    * @return ハッシュ値
    */
  def getHash(cert:X509Certificate):BigInt = {
    BigInt(MessageDigest.getInstance(HashAlgorithm).digest(cert.getEncoded))
  }

}