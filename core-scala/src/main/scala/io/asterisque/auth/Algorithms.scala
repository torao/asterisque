package io.asterisque.auth

import java.io.{ByteArrayInputStream, File, FileInputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.cert._
import java.security.spec.PKCS8EncodedKeySpec
import java.security.{KeyFactory, PrivateKey, PublicKey, Signature}
import java.util
import java.util.Base64

import io.asterisque.carillon.using
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

object Algorithms {

  val Key = "EC"

  object Principal {
    val Signature = "SHA3-512withECDSA"
  }

  def sign(privateKey:PrivateKey, data:Array[Byte], algorithm:String):Array[Byte] = {
    val signature = Signature.getInstance(algorithm)
    signature.initSign(privateKey)
    signature.update(data)
    signature.sign()
  }

  def verify(publicKey:PublicKey, data:Array[Byte], sign:Array[Byte], algorithm:String):Boolean = {
    val signature = Signature.getInstance(algorithm)
    signature.initVerify(publicKey)
    signature.update(data)
    signature.verify(sign)
  }

  val X509Factory:CertificateFactory = CertificateFactory.getInstance("X.509")

  object Certificate {
    def load(file:File):X509Certificate = using(new FileInputStream(file)) { in =>
      X509Factory.generateCertificate(in).asInstanceOf[X509Certificate]
    }

    def load(binary:Array[Byte]):X509Certificate = {
      X509Factory.generateCertificate(new ByteArrayInputStream(binary)).asInstanceOf[X509Certificate]
    }
  }

  object CertificateChain {

    /** Decoding algorithm for certificate chain by [[CertificateChain.load()]] */
    val ENCODING = "PKCS7"

    /** Validation algorithm for certificate chain by [[CertificateChain.validate()]]. */
    val VALIDATE = "PKIX"

    /**
      * Restore certificate chain from specified binary in PKCS#7 PEM format.
      *
      * @param bytes binary of PKCS#7
      * @return certificate chain
      */
    def load(bytes:Array[Byte]):CertPath = {
      X509Factory.generateCertPath(new ByteArrayInputStream(bytes), ENCODING)
    }

    def load(file:File):CertPath = load(Files.readAllBytes(file.toPath))

    /**
      * Validate the specified certificate chain. Returns true if all certificates contained in the chain are
      * valid and are certificates issued by the previous CA certificate. Note that certificate chains of length
      * 0 or 1 are also considered valid.
      *
      * @param certPath certificate chain to validate
      * @return the result of validation
      */
    def validate(trustAnchors:Set[TrustAnchor], certPath:CertPath):Try[X509Certificate] = {
      val certs = certPath.getCertificates.asScala.map(_.asInstanceOf[X509Certificate])
      val params = new PKIXParameters(trustAnchors.asJava)
      val validator = CertPathValidator.getInstance(VALIDATE)
      val revocationChecker = validator.getRevocationChecker.asInstanceOf[PKIXRevocationChecker]
      revocationChecker.setOptions(util.EnumSet.of(PKIXRevocationChecker.Option.ONLY_END_ENTITY))
      params.addCertPathChecker(revocationChecker)
      Try(validator.validate(certPath, params).asInstanceOf[PKIXCertPathValidatorResult]).map { result =>
        result.getTrustAnchor.getTrustedCert
      }.flatMap { trustedCert =>
        certs.map(cert => Try(cert.checkValidity())).collectFirst { case f:Failure[_] => f } match {
          case Some(Failure(err)) => Failure(err)
          case None => Success(trustedCert)
        }
      }.flatMap { trustedCert =>
        certs.zip(certs.drop(1)).map { case (subject, issuer) =>
          Try(subject.verify(issuer.getPublicKey))
        }.collectFirst { case f:Failure[_] => f } match {
          case Some(Failure(err)) => Failure(err)
          case None => Success(trustedCert)
        }
      }
    }
  }

  object CRL {
    def load(encoded:Array[Byte]):X509CRL = {
      Algorithms.X509Factory.generateCRL(new ByteArrayInputStream(encoded)).asInstanceOf[X509CRL]
    }
  }

  object PrivateKey {

    /**
      * Restores private key from the specified binary in PEM or DER format.
      *
      * @param bytes private-key binary
      * @return private key
      */
    def load(bytes:Array[Byte]):PrivateKey = {
      def deserializeDER(bytes:Array[Byte]):PrivateKey = {
        val spec = new PKCS8EncodedKeySpec(bytes, Algorithms.Key)
        val factory = KeyFactory.getInstance(Algorithms.Key)
        factory.generatePrivate(spec)
      }

      // supports DER or PEM contained DER binary
      deserializeDER(
        PEM.parse(bytes).find(e => e.name == "PRIVATE KEY" || e.name == "EC PRIVATE KEY") match {
          case Some(entry) => entry.content
          case None => bytes
        })
    }

    def load(file:File):PrivateKey = load(Files.readAllBytes(file.toPath))
  }

  object PEM {
    private[this] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))

    /**
      * PEM のエントリを抽出する正規表現。
      */
    private[this] val ENTRY = "(?m)(?s)-----BEGIN\\s+(.*?)-----\r?\n+(.*?)\r?\n-----END\\s+(.*?)-----".r

    /**
      * Class representing the entry stored in PEM.
      *
      * @param name       the name entry (e.g., "EC PRIVATE KEY", "CERTIFICATE")
      * @param attributes attributes
      * @param content    binary of entry
      */
    case class Entry(name:String, attributes:Map[String, String], content:Array[Byte])

    /**
      * Parse PEM container format and obtain internal entries. If the binary isn't PEM format like DER,
      * this will return a zero length `Seq`.
      *
      * @param bytes PEM format binary
      * @return entries that the PEM contains
      */
    def parse(bytes:Array[Byte]):Seq[Entry] = ENTRY.findAllMatchIn(new String(bytes, StandardCharsets.UTF_8))
      .flatMap { m =>
        val begin = m.group(1).trim().toUpperCase
        val end = m.group(3).trim().toUpperCase
        val body = m.group(2)
        if(begin == end) {
          // TODO should extract attributes from body
          Some(Entry(begin, Map.empty, Base64.getMimeDecoder.decode(body)))
        } else {
          logger.warn(s"PEM entry separators don't match: '$begin' != '$end'")
          None
        }
      }.toSeq
  }

}
