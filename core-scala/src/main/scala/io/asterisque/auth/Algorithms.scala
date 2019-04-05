package io.asterisque.auth

import java.io.{ByteArrayInputStream, File, FileInputStream, FileOutputStream}
import java.nio.file.Files
import java.security.cert._
import java.security.{PrivateKey, PublicKey, Signature}
import java.util

import io.asterisque.carillon.using

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

  /** use [[io.asterisque.security.Algorithms]] */
  @Deprecated
  object Certificate {
    def load(file:File):X509Certificate = load(Files.readAllBytes(file.toPath))

    def load(binary:Array[Byte]):X509Certificate = {
      X509Factory.generateCertificate(new ByteArrayInputStream(binary)).asInstanceOf[X509Certificate]
    }

    def loads(file:File):Seq[X509Certificate] = loads(Files.readAllBytes(file.toPath))

    def loads(binary:Array[Byte]):Seq[X509Certificate] = X509Factory
      .generateCertificates(new ByteArrayInputStream(binary)).asScala
      .map(_.asInstanceOf[X509Certificate]).toList

    def store(file:File, cert:X509Certificate):Unit = using(new FileOutputStream(file)) { out =>
      out.write(cert.getEncoded)
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

    def generate(certs:Seq[X509Certificate]) = X509Factory.generateCertPath(certs.asJava)

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

  /** use [[io.asterisque.security.Algorithms]] */
  @Deprecated
  val CRL = io.asterisque.security.Algorithms.CRL

  /** use [[io.asterisque.security.Algorithms]] */
  @Deprecated
  val PrivateKey = io.asterisque.security.Algorithms.PrivateKey

  /** use [[io.asterisque.security.Algorithms]] */
  @Deprecated
  object KeyStore {
    def load(file:File, passphrase:String):java.security.KeyStore = {
      val keyStore = java.security.KeyStore.getInstance("PKCS12")
      val in = new FileInputStream(file)
      keyStore.load(in, passphrase.toCharArray)
      keyStore
    }
  }

}
