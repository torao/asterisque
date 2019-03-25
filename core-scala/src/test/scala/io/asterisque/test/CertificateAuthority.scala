package io.asterisque.test

import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

import io.asterisque.auth.Algorithms
import io.asterisque.tools.pki.CA


class CertificateAuthority() extends AutoCloseable {
  val dir:File = fs.createTempDirectory(this)
  val ca:CA = new CA(dir)
  ca.init()

  override def close():Unit = fs.removeDirectory(dir)

  def newPrivateKeyAndCertificate(cname:String):(PrivateKey, X509Certificate) = {
    val key = File.createTempFile("private", ".pem", dir)
    val cert = File.createTempFile("certificate", ".pem", dir)
    ca.newKeyWithCertificate(key, cert, s"/C=JP/ST=Tokyo/L=Sumida/O=Carillon/OU=dev/CN=$cname")
    (Algorithms.PrivateKey.load(key), Algorithms.Certificate.load(cert))
  }
}
