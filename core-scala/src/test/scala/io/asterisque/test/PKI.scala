package io.asterisque.test

import java.io.File

import io.asterisque.auth.Algorithms
import io.asterisque.tools.pki.CA

@deprecated("use io.asterisque.tools.PKI.CA")
class PKI(
           rootCASubject:String = PKI.subject("root.ca.com"),
           _intermediateCA:String = "intermeciate.com", _server:String = "server.com", _client:String = "client.com") {

  lazy val rootCA:KeyCertPair = PKI.newSelfSignedKeyCertPair(rootCASubject)

}

@deprecated("use io.asterisque.tools.PKI.CA")
object PKI {

  val dir:File = fs.createTempDirectory(this)
  val ca:CA = new CA(dir)
  ca.init()

  Runtime.getRuntime.addShutdownHook(new Thread() {
    override def run():Unit = fs.removeDirectory(dir)
  })

  /**
    * 指定された Subject を持つ自己署名証明書とその秘密鍵を新たに作成します。
    *
    * @param subject 自己署名証明書の Subject
    * @return 新しい秘密鍵と自己署名証明書
    */
  def newSelfSignedKeyCertPair(subject:String):KeyCertPair = {
    val key = File.createTempFile("private", ".pem", dir)
    val cert = File.createTempFile("certificate", ".pem", dir)
    ca.newKeyWithCertificate(key, cert, subject)
    val pair = KeyCertPair(Algorithms.PrivateKey.load(key), Algorithms.Certificate.load(cert))
    key.delete()
    cert.delete()
    pair
  }

  def newKeyCertPair(issuer:KeyCertPair, subject:String):KeyCertPair = {
    val key = File.createTempFile("private", ".pem", dir)
    ca.newKey(key)

    val caKey = File.createTempFile("cakey", ".pem", dir)
    val caCert = File.createTempFile("cacert", ".pem", dir)
    Algorithms.PrivateKey.store(caKey, issuer.key)
    Algorithms.Certificate.store(caCert, issuer.certificate)
    val cert = File.createTempFile("certificate", ".pem", dir)
    ca.newKeyWithCertificate(key, cert, subject)
    val pair = KeyCertPair(Algorithms.PrivateKey.load(key), Algorithms.Certificate.load(cert))
    key.delete()
    cert.delete()
    pair
  }

  /**
    * 指定された CNAME を持つテスト用の DN を作成するユーティリティです。
    *
    * @param cname CNAME
    * @return テスト用の DN
    */
  def subject(cname:String):String = s"/C=JP/ST=Tokyo/L=Sumida/O=Asterisque/OU=QA Division/CN=$cname"

}