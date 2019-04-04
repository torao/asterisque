package io.asterisque.tools

import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

import io.asterisque.auth.Algorithms
import io.asterisque.test.fs._
import javax.security.auth.x500.X500Principal
import org.apache.commons.codec.binary.Hex
import org.slf4j.LoggerFactory
import org.specs2.Specification

import scala.collection.JavaConverters._

class PKISpec extends Specification {
  def is =
    s2"""
Openssl function throws exception that exit without zero. $opensslThrowsExceptionOnError
CA can mount existing CA repository. $mountExistingCA
It can create and destroy CA. $caInitAndDestroy
It can create intermediate CA and issue certificate. $issueCertificateFromCA
It can revoke certificate and export PKCS#7. $revokeCertificate
"""

  private[this] val logger = LoggerFactory.getLogger(getClass)

  private[this] val CA_SUBJECT = "/C=JP/ST=Tokyo/L=Sumida/O=Asterisque Ltd./OU=QA Division/CN=ca.asterisque.io"
  private[this] val USER_SUBJECT = "/C=JP/ST=Tokyo/L=Sumida/O=Asterisque Ltd./OU=QA Division/CN=user.asterisque.io"

  private[this] def opensslThrowsExceptionOnError = {
    PKI.openssl(sh"""pkcs7 -in file_not_exists.pem -print_certs""") must throwA[IllegalStateException]
  }

  private[this] def mountExistingCA = temp(this) { dir =>
    val ca1 = PKI.CA.newRootCA(dir, CA_SUBJECT)
    val ca1Key = Algorithms.PrivateKey.load(ca1.privateKeyFile)
    val ca1Cert = Algorithms.Certificate.load(ca1.certFile)

    val ca2 = PKI.CA(dir)
    val ca2Key = Algorithms.PrivateKey.load(ca2.privateKeyFile)
    val ca2Cert = Algorithms.Certificate.load(ca2.certFile)

    (ca1Key === ca2Key) and (ca1Cert === ca2Cert)
  }

  private[this] def caInitAndDestroy = temp(this) { dir =>
    val ca = PKI.CA.newRootCA(dir, CA_SUBJECT)
    val subject = "CN=ca.asterisque.io,OU=QA Division,O=Asterisque Ltd.,ST=Tokyo,C=JP"

    val cert = Algorithms.Certificate.load(ca.certFile)
    logger.info(s"""[CA Certificate]""")
    logger.info(s"""Version            : ${cert.getVersion}""")
    logger.info(s"""Serial Number      : ${cert.getSerialNumber}""")
    logger.info(s"""Sign Algorithm Name: ${cert.getSigAlgName}""")
    cert.checkValidity()

    val key = Algorithms.PrivateKey.load(ca.privateKeyFile)
    logger.info(s"""[CA Private Key]""")
    logger.info(s"""Algorithm: ${key.getAlgorithm}""")
    logger.info(s"""Format   : ${key.getFormat}""")
    logger.info(s"""Encoded  : ${Hex.encodeHexString(key.getEncoded)}""")
    logger.info(s"""Size     : ${key.getEncoded.length}""")

    ca.destroy()
    (cert.getSubjectX500Principal.getName(X500Principal.RFC2253) === subject) and
      (dir.exists() must beFalse)
  }

  private[this] def issueCertificateFromCA = temp(this) { dir =>
    val userSubject = "CN=user.asterisque.io,OU=QA Division,O=Asterisque Ltd.,ST=Tokyo,C=JP"
    val rootSubject = "CN=ca.asterisque.io,OU=QA Division,O=Asterisque Ltd.,ST=Tokyo,C=JP"
    val ca1Subject = "CN=ca1.asterisque.io,OU=QA Division,O=Asterisque Ltd.,ST=Tokyo,C=JP"
    val ca2Subject = "CN=ca2.asterisque.io,OU=QA Division,O=Asterisque Ltd.,ST=Tokyo,C=JP"

    val root = PKI.CA.newRootCA(new File(dir, "root"), CA_SUBJECT)
    val ca1 = PKI.CA.newIntermediateCA(root, new File(dir, "ca1"),
      "/C=JP/ST=Tokyo/L=Sumida/O=Asterisque Ltd./OU=QA Division/CN=ca1.asterisque.io")
    val ca2 = PKI.CA.newIntermediateCA(ca1, new File(dir, "ca2"),
      "/C=JP/ST=Tokyo/L=Sumida/O=Asterisque Ltd./OU=QA Division/CN=ca2.asterisque.io")

    val pkcs12File = new File(dir, "user.p12")
    ca2.newPKCS12CertificateWithKey(pkcs12File, "user", "****", USER_SUBJECT)
    val keyStore = Algorithms.KeyStore.load(pkcs12File, "****")

    val cert = keyStore.getCertificate("user").asInstanceOf[X509Certificate]
    logger.info(s"""[Use Certificate]""")
    logger.info(s"""Version            : ${cert.getVersion}""")
    logger.info(s"""Serial Number      : ${cert.getSerialNumber}""")
    logger.info(s"""Sign Algorithm Name: ${cert.getSigAlgName}""")
    cert.checkValidity()

    val key = keyStore.getKey("user", "****".toCharArray).asInstanceOf[PrivateKey]
    logger.info(s"""[User Private Key]""")
    logger.info(s"""Algorithm: ${key.getAlgorithm}""")
    logger.info(s"""Format   : ${key.getFormat}""")
    logger.info(s"""Encoded  : ${Hex.encodeHexString(key.getEncoded)}""")
    logger.info(s"""Size     : ${key.getEncoded.length}""")

    val certPath = keyStore.getCertificateChain("user").map(_.asInstanceOf[X509Certificate])

    (Algorithms.Certificate.load(root.certFile).getSubjectX500Principal.getName === rootSubject) and
      (Algorithms.Certificate.load(root.certFile).getIssuerX500Principal.getName === rootSubject) and
      (Algorithms.Certificate.load(ca1.certFile).getSubjectX500Principal.getName === ca1Subject) and
      (Algorithms.Certificate.load(ca1.certFile).getIssuerX500Principal.getName === rootSubject) and
      (Algorithms.Certificate.load(ca2.certFile).getSubjectX500Principal.getName === ca2Subject) and
      (Algorithms.Certificate.load(ca2.certFile).getIssuerX500Principal.getName === ca1Subject) and
      (cert.getSubjectX500Principal.getName === userSubject) and
      (cert.getIssuerX500Principal.getName === ca2Subject) and
      (certPath.length === 4) and
      (certPath.head.getSubjectX500Principal.getName === userSubject) and
      (certPath.head.getIssuerX500Principal.getName === ca2Subject) and
      (certPath(1).getSubjectX500Principal.getName === ca2Subject) and
      (certPath(1).getIssuerX500Principal.getName === ca1Subject) and
      (certPath(2).getSubjectX500Principal.getName === ca1Subject) and
      (certPath(2).getIssuerX500Principal.getName === rootSubject) and
      (certPath(3).getSubjectX500Principal.getName === rootSubject) and
      (certPath(3).getIssuerX500Principal.getName === rootSubject)
  }

  private[this] def revokeCertificate = temp(this) { dir =>
    val rootSubject = "CN=root.asterisque.io,OU=QA Division,O=Asterisque Ltd.,ST=Tokyo,C=JP"
    val ca1Subject = "CN=ca1.asterisque.io,OU=QA Division,O=Asterisque Ltd.,ST=Tokyo,C=JP"
    val ca2Subject = "CN=ca2.asterisque.io,OU=QA Division,O=Asterisque Ltd.,ST=Tokyo,C=JP"

    val root = PKI.CA.newRootCA(new File(dir, "root"),
      "/C=JP/ST=Tokyo/L=Sumida/O=Asterisque Ltd./OU=QA Division/CN=root.asterisque.io")
    val ca1 = PKI.CA.newIntermediateCA(root, new File(dir, "ca1"),
      "/C=JP/ST=Tokyo/L=Sumida/O=Asterisque Ltd./OU=QA Division/CN=ca1.asterisque.io")
    val ca2 = PKI.CA.newIntermediateCA(ca1, new File(dir, "ca2"),
      "/C=JP/ST=Tokyo/L=Sumida/O=Asterisque Ltd./OU=QA Division/CN=ca2.asterisque.io")
    val ca2Cert = Algorithms.Certificate.load(ca2.certFile)

    // PKCS#12: private key and certificate
    val pkcs12File = new File(dir, "user.p12")
    val certPEMFile = new File(dir, "user-cert.pem")
    ca2.newPKCS12CertificateWithKey(pkcs12File, "user", "****", USER_SUBJECT)
    PKI.CA.exportCertificateAsPEM(pkcs12File, "****", certPEMFile)
    val cert = Algorithms.Certificate.load(certPEMFile)

    // PKCS#7: CA Certificate Path and CRL
    val certPathFile = new File(dir, "certpath.p7b")
    ca2.exportCertPathWithCRLAsPKCS7(certPathFile)
    val crls = Algorithms.CRL.loads(certPathFile)

    // PEM: CRL
    val crlFile = new File(dir, "crl.pem")
    ca2.revokeCertificate(certPEMFile)
    ca2.exportCRLAsPEM(crlFile)

    val certPath = Algorithms.CertificateChain.load(certPathFile)
      .getCertificates.asScala.map(_.asInstanceOf[X509Certificate])

    val pemExportedCRL = Algorithms.CRL.loads(crlFile).head
    val pemExportedCRLEntries = pemExportedCRL.getRevokedCertificates.asScala

    (certPath.length === 3) and
      (certPath.head.getSubjectX500Principal.getName === ca2Subject) and
      (certPath.head.getIssuerX500Principal.getName === ca1Subject) and
      (certPath(1).getSubjectX500Principal.getName === ca1Subject) and
      (certPath(1).getIssuerX500Principal.getName === rootSubject) and
      (certPath(2).getSubjectX500Principal.getName === rootSubject) and
      (certPath(2).getIssuerX500Principal.getName === rootSubject) and
      (crls.size == 1) and
      (crls.head.getIssuerX500Principal.getName === ca2Subject) and {
      crls.head.verify(ca2Cert.getPublicKey)
      success
    } and
      (pemExportedCRL.getIssuerX500Principal.getName === ca2Subject) and {
      pemExportedCRL.verify(ca2Cert.getPublicKey)
      success
    } and
      (pemExportedCRLEntries.size === 1) and
      (pemExportedCRLEntries.head.getSerialNumber === cert.getSerialNumber)
  }

}
