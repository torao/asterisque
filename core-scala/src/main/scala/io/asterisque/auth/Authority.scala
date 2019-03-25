package io.asterisque.auth

import java.io._
import java.net.Socket
import java.security.cert._

import io.asterisque.auth.Authority.logger
import io.asterisque.utils.KeyValueStore
import javax.net.ssl.{SSLEngine, X509ExtendedTrustManager}
import org.slf4j.LoggerFactory

import scala.util.Try

class Authority(rootCertsDirectory:File, cache:KeyValueStore) {
  logger.debug(s"initializing authority: $rootCertsDirectory")

  private[this] val trusted = new TrustedCertsStore(this, rootCertsDirectory, cache.subset("cert-chain."))

  private[this] val blocked = new BlockedCertsStore(this, cache.subset("crl."))

  /**
    * Refer to the valid CA certificates stored in this authority.
    *
    * @return CA certificates
    */
  def getCACertificates:Seq[X509Certificate] = trusted.getCACertificates
    .filterNot(cert => blocked.isBlocking(cert))

  /**
    * Refer to the valid role certificates stored in this authority.
    *
    * @param role role
    * @return roled certificates
    */
  def getRoleCertificates(role:String):Seq[X509Certificate] = trusted.getRoleCertificates(role)
    .filterNot(cert => blocked.isBlocking(cert))

  /**
    * Find CA Certificate that issued the specified certificate. It returns None if it doesn't exist in the CA
    * list managed by this instance or has already been revoked.
    *
    * @param cert the certificate to find issuer
    * @return CA certificate that issued the specified certificate
    */
  def findIssuer(cert:X509Certificate):Option[X509Certificate] = {
    getCACertificates.find(ca => Try(cert.verify(ca.getPublicKey)).isSuccess)
  }

  def reload():Unit = {
    trusted.reload()
    blocked.reload()
  }

  def install(from:String, iccs:Seq[CertPath], crls:Seq[X509CRL]):Unit = {
    trusted.install(from, iccs)
    blocked.install(from, crls)
  }

  /**
    * Refers to `TrustManager` for performing SSL/TLS communication using a certificate managed by this certificate
    * authority.
    */
  object trustManager extends X509ExtendedTrustManager {
    override def checkClientTrusted(chain:Array[X509Certificate], authType:String, socket:Socket):Unit = {
      checkTrusted(chain)
    }

    override def checkServerTrusted(chain:Array[X509Certificate], authType:String, socket:Socket):Unit = {
      checkTrusted(chain)
    }

    override def checkClientTrusted(chain:Array[X509Certificate], authType:String, engine:SSLEngine):Unit = {
      checkTrusted(chain)
    }

    override def checkServerTrusted(chain:Array[X509Certificate], authType:String, engine:SSLEngine):Unit = {
      checkTrusted(chain)
    }

    override def checkClientTrusted(chain:Array[X509Certificate], authType:String):Unit = {
      checkTrusted(chain)
    }

    override def checkServerTrusted(chain:Array[X509Certificate], authType:String):Unit = {
      checkTrusted(chain)
    }

    private[this] def checkTrusted(chain:Array[X509Certificate]):Unit = {
      if(chain.filter(cert => Try(cert.checkValidity()).isSuccess).flatMap(cert => findIssuer(cert)).isEmpty) {
        throw new CertificateException("no valid certificate")
      }
    }

    override def getAcceptedIssuers:Array[X509Certificate] = getCACertificates.toArray
  }

  reload()
}

object Authority {
  private[Authority] val logger = LoggerFactory.getLogger(classOf[Authority])

  val ROLE_CA:String = "ca"

  class ValidityException(message:String, ex:Throwable*) extends Exception(if(ex.length > 1) {
    Option(message).map(_ +: ex.map(_.toString)).getOrElse(ex.map(_.toString)).mkString("\n")
  } else message, ex.headOption.orNull) {
    def this() = this(null:String, Seq.empty:_*)

    def this(message:String) = this(message, Seq.empty:_*)
  }

}