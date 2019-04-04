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

class PKISpec extends Specification {
  def is =
    s2"""
It can create and destroy CA. $caInitAndDestroy
It can create intermediate CA and issue certificate. $issueCertificateFromCA
"""

  private[this] val logger = LoggerFactory.getLogger(getClass)

  private[this] val SUBJECT = "/C=JP/ST=Tokyo/L=Sumida/O=Asterisque Ltd./OU=QA Division/CN=ut.asterisque.io"
  private[this] val CA_SUBJECT = "/C=JP/ST=Tokyo/L=Sumida/O=Asterisque Ltd./OU=QA Division/CN=ca.asterisque.io"
  private[this] val USER_SUBJECT = "/C=JP/ST=Tokyo/L=Sumida/O=Asterisque Ltd./OU=QA Division/CN=user.asterisque.io"

  private[this] def caInitAndDestroy = temp(this) { dir =>
    val ca = new PKI.CA(dir)
    val subject = "CN=ca.asterisque.io,OU=QA Division,O=Asterisque Ltd.,ST=Tokyo,C=JP"
    ca.initRoot(CA_SUBJECT)

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
      (dir.listFiles().isEmpty must beTrue)
  }

  private[this] def issueCertificateFromCA = temp(this) { dir =>
    val userSubject = "CN=user.asterisque.io,OU=QA Division,O=Asterisque Ltd.,ST=Tokyo,C=JP"
    val rootSubject = "CN=ca.asterisque.io,OU=QA Division,O=Asterisque Ltd.,ST=Tokyo,C=JP"
    val ca1Subject = "CN=ca1.asterisque.io,OU=QA Division,O=Asterisque Ltd.,ST=Tokyo,C=JP"
    val ca2Subject = "CN=ca2.asterisque.io,OU=QA Division,O=Asterisque Ltd.,ST=Tokyo,C=JP"

    val root = new PKI.CA(new File(dir, "root"))
    val ca1 = new PKI.CA(new File(dir, "ca1"))
    val ca2 = new PKI.CA(new File(dir, "ca2"))
    root.initRoot(CA_SUBJECT)
    ca1.initIntermediate(root, "/C=JP/ST=Tokyo/L=Sumida/O=Asterisque Ltd./OU=QA Division/CN=ca1.asterisque.io")
    ca2.initIntermediate(ca1, "/C=JP/ST=Tokyo/L=Sumida/O=Asterisque Ltd./OU=QA Division/CN=ca2.asterisque.io")

    val pkcs12File = new File(dir, "user.p12")
    ca2.newPKCS12CertificateWithKey(pkcs12File, "user", "****", USER_SUBJECT)
    val keyStore = Algorithms.KeyStore.load(pkcs12File, "****")

    val userCertificate = keyStore.getCertificate("user").asInstanceOf[X509Certificate]
    logger.info(s"""[Use Certificate]""")
    logger.info(s"""Version            : ${userCertificate.getVersion}""")
    logger.info(s"""Serial Number      : ${userCertificate.getSerialNumber}""")
    logger.info(s"""Sign Algorithm Name: ${userCertificate.getSigAlgName}""")
    userCertificate.checkValidity()

    val userKey = keyStore.getKey("user", "****".toCharArray).asInstanceOf[PrivateKey]
    logger.info(s"""[User Private Key]""")
    logger.info(s"""Algorithm: ${userKey.getAlgorithm}""")
    logger.info(s"""Format   : ${userKey.getFormat}""")
    logger.info(s"""Encoded  : ${Hex.encodeHexString(userKey.getEncoded)}""")
    logger.info(s"""Size     : ${userKey.getEncoded.length}""")

    (Algorithms.Certificate.load(root.certFile).getSubjectX500Principal.getName === rootSubject) and
      (Algorithms.Certificate.load(root.certFile).getIssuerX500Principal.getName === rootSubject) and
      (Algorithms.Certificate.load(ca1.certFile).getSubjectX500Principal.getName === ca1Subject) and
      (Algorithms.Certificate.load(ca1.certFile).getIssuerX500Principal.getName === rootSubject) and
      (Algorithms.Certificate.load(ca2.certFile).getSubjectX500Principal.getName === ca2Subject) and
      (Algorithms.Certificate.load(ca2.certFile).getIssuerX500Principal.getName === ca1Subject) and
      (userCertificate.getSubjectX500Principal.getName === userSubject) and
      (userCertificate.getIssuerX500Principal.getName === ca2Subject)
  }

}
