package io.asterisque.carillon.auth

import java.io.{File, FileOutputStream}
import java.security.cert.X509Certificate
import java.util.Random

import io.asterisque.auth.{Algorithms, Authority}
import io.asterisque.carillon.Utils._
import io.asterisque.carillon._
import io.asterisque.tools.pki.CA
import io.asterisque.utils.KeyValueStore
import org.specs2.Specification
import org.specs2.matcher.MatchResult
import play.api.libs.json.Json

class TrustCertsStoreSpec extends Specification {
  def is = sequential ^
    s2"""
It can load trusted X.509 certificates from local. $loadTrustedCerts
It does not use an expired certificate as trusted. $expiredCert
It can accept dynamically installed CA certificate. $intermediateCertificateChain
It shouldn't accept the certificate chain that contains expired cert. $installInvalidCertChain
It should skip the same certificate chain to install. $installExistingCertChain
It should remove certificate chain from cache when it invalidate while begin cache. $certChainInvalidatedWhileBeingCached
It should skip the unsupported trust anchor CA cert format. $unsupportedTrustedCACerts
    """

  private[this] def loadTrustedCerts = fs.temp(this) { root =>
    val ca = new CA(new File(root, "ca"))
    ca.init()

    val trusted = new File(root, "trusted")
    trusted.mkdirs()

    val certs = ((0 until 3).map { i =>
      val cname = f"$i%02d.carillon.asterisque.io"
      val basename = f"$i%02d"
      val key = new File(trusted, s"$basename.key")
      val csr = new File(trusted, s"$basename.csr")
      val crt = new File(trusted, s"$basename.crt")
      ca.newKeyWithSelfSignedCertificate(key, csr, crt, cname)
      crt
    } :+ {
      fs.copy(ca.certFile, new File(trusted, "root-ca.crt"))
      ca.certFile
    }).map(Algorithms.Certificate.load)

    using(KeyValueStore(new File(root, "cache"))) { kvs =>
      val auth = new Authority(trusted, kvs)
      certs.map { cert =>
        auth.findIssuer(cert).contains(cert) must beTrue
      }.reduceLeft(_ and _)
    }
  }

  private[this] def expiredCert = fs.temp(this) { root =>
    val ca = new CA(new File(root, "ca"))
    ca.init()

    val trusted = new File(root, "trusted")
    trusted.mkdirs()

    ca.newKeyWithSelfSignedCertificate(
      new File(trusted, "valid.key"),
      new File(trusted, "valid.csr"),
      new File(trusted, "valid.crt"),
      "valid/A=b,C=d")
    val valid = Algorithms.Certificate.load(new File(trusted, "valid.crt"))

    ca.newKeyWithSelfSignedCertificate(
      new File(trusted, "expired.key"),
      new File(trusted, "expired.csr"),
      new File(trusted, "expired.crt"),
      "expired/A=b,C=d", -1)

    using(KeyValueStore(new File(root, "cache"))) { kvs =>
      val auth = new Authority(trusted, kvs)
      (auth.getCACertificates.size === 1) and (auth.getCACertificates.head === valid)
    }
  }

  private[this] def intermediateCertificateChain = fs.temp(this) { root =>
    val ca = new CA(new File(root, "ca"))
    ca.init()

    val trusted = new File(root, "trusted")
    trusted.mkdirs()
    val intermediate = new File(root, "certs")
    intermediate.mkdirs()

    fs.copy(ca.certFile, new File(trusted, "trusted-ca.crt"))

    val ica01 = ca.childCA(new File(ca.dir, "ca/ica01"), dname("ica01", title = "ca"))
    val ica02 = ica01.childCA(new File(ca.dir, "ca/ica02"), dname("ica02", title = "ca/title=x"))

    val pkcs7 = new File(intermediate, "cert.p7b")
    ca.newPKCS7CertChain(Seq(ca.certFile, ica01.certFile, ica02.certFile), pkcs7)
    val certPath = Algorithms.CertificateChain.load(pkcs7)

    val keyFile = new File(root, "node.key")
    val certFile = new File(root, "node.crt")
    ica02.newKeyWithCertificate(keyFile, certFile, dname("node"))
    val nodeCert = Algorithms.Certificate.load(certFile)

    using(KeyValueStore(new File(root, "cache"))) { kvs =>
      val auth = new Authority(trusted, kvs)
      auth.install("unit-test", Seq(certPath), Seq.empty)
      (auth.getCACertificates.size === 2) and
        (auth.getRoleCertificates("CA").size === 1) and
        (auth.getRoleCertificates("CA").head === ica02.cert) and
        (auth.getRoleCertificates("X").size === 1) and
        (auth.getRoleCertificates("X").head === ica02.cert) and
        (auth.findIssuer(nodeCert).isDefined must beTrue).setMessage(Json.prettyPrint(nodeCert.toJSON))
    }
  }

  private[this] def installInvalidCertChain = fs.temp(this) { root =>
    // index.txt を別にしないと openssl が証明書の作成に失敗する
    val ca = new CA(new File(root, "ca"))
    ca.init()

    val trusted = new File(root, "trusted")
    trusted.mkdirs()
    val intermediate = new File(root, "certs")
    intermediate.mkdirs()

    fs.copy(ca.certFile, new File(trusted, "trusted-ca.crt"))

    val ica01 = ca.childCA(new File(ca.dir, "ca/ica01"), dname("ica01", title = "ca"), -1)
    val ica02 = ica01.childCA(new File(ca.dir, "ca/ica02"), dname("ica02", title = "ca"))

    val pkcs7 = new File(intermediate, "cert.p7b")
    ca.newPKCS7CertChain(Seq(ca.certFile, ica01.certFile, ica02.certFile), pkcs7)
    val certPath = Algorithms.CertificateChain.load(pkcs7)

    using(KeyValueStore(new File(root, "cache"))) { kvs =>
      val auth = new Authority(trusted, kvs)
      auth.install("unit-test", Seq(certPath), Seq.empty)
      (auth.getCACertificates.size === 1) and
        (auth.getCACertificates.head === ca.cert) and
        (auth.getRoleCertificates("CA").size === 0)
    }
  }

  private[this] def installExistingCertChain = fs.temp(this) { root =>
    val ca = new CA(new File(root, "ca"))
    ca.init()
    val trusted = new File(root, "trusted")
    trusted.mkdirs()
    val intermediate = new File(root, "certs")
    intermediate.mkdirs()

    fs.copy(ca.certFile, new File(trusted, "trusted-ca.crt"))

    val ica01 = ca.childCA(new File(ca.dir, "ca/ica01"), dname("ica01", title = "ca"))

    val pkcs7 = new File(intermediate, "cert.p7b")
    ca.newPKCS7CertChain(Seq(ca.certFile, ica01.certFile), pkcs7)
    val certPath = Algorithms.CertificateChain.load(pkcs7)

    using(KeyValueStore(new File(root, "cache"))) { kvs =>
      val auth = new Authority(trusted, kvs)
      auth.install("unit-test", Seq(certPath), Seq.empty)
      auth.install("unit-test", Seq(certPath), Seq.empty)
      (auth.getCACertificates.size === 2) and
        auth.getCACertificates.areAllContainedIn(ca.cert, ica01.cert) and
        (auth.getRoleCertificates("CA").size === 1) and
        (auth.getRoleCertificates("CA").head === ica01.cert)
    }
  }

  private[this] def certChainInvalidatedWhileBeingCached = fs.temp(this) { root =>
    val ca = new CA(new File(root, "ca"))
    ca.init()
    val trusted = new File(root, "trusted")
    trusted.mkdirs()

    val ica01 = new CA(new File(root, "ca/ica01"))
    ica01.init()
    val ica02 = ica01.childCA(new File(ca.dir, "ca/ica01"), dname("ica01", title = "ca"))

    fs.copy(ca.certFile, new File(trusted, "trusted-ca.crt"))
    fs.copy(ica01.certFile, new File(trusted, "trusted-ica01.crt"))

    val pkcs7 = new File(root, "cert.p7b")
    ca.newPKCS7CertChain(Seq(ica01.certFile, ica02.certFile), pkcs7)
    val certPath = Algorithms.CertificateChain.load(pkcs7)

    using(KeyValueStore(new File(root, "cache"))) { kvs =>
      val auth = new Authority(trusted, kvs)
      auth.install("unit-test", Seq(certPath), Seq.empty)
      val beforeExpected = (auth.getCACertificates.size === 3) and
        auth.getCACertificates.areAllContainedIn(ca.cert, ica01.cert, ica02.cert) and
        (auth.getRoleCertificates("CA").size === 1) and
        (auth.getRoleCertificates("CA").head === ica02.cert)

      // 証明書チェーンの trust anchor を削除して再読込
      new File(trusted, "trusted-ica01.crt").delete()
      auth.reload()
      val afterExpected = (auth.getCACertificates.size === 1) and
        auth.getCACertificates.areAllContainedIn(ca.cert) and
        (auth.getRoleCertificates("CA").size === 0)

      beforeExpected and afterExpected
    }
  }

  private[this] def unsupportedTrustedCACerts = fs.temp(this) { root =>
    val ca = new CA(new File(root, "ca"))
    ca.init()
    val trusted = new File(root, "trusted")
    trusted.mkdirs()

    fs.copy(ca.certFile, new File(trusted, "trusted-ca.crt"))

    // .crt 拡張子を持つランダムなバイトのファイルを作成
    val random = new Random(3487037L)
    using(new FileOutputStream(new File(trusted, "random.crt"))) { out =>
      val buffer = new Array[Byte](1024)
      (0 until 1024).foreach { i =>
        random.nextBytes(buffer)
        out.write(buffer)
      }
    }

    using(KeyValueStore(new File(root, "cache"))) { kvs =>
      val auth = new Authority(trusted, kvs)
      (auth.getCACertificates.size === 1) and
        auth.getCACertificates.areAllContainedIn(ca.cert) and
        (auth.getRoleCertificates("CA").size === 0)
    }
  }

  implicit class _SeqX509Certificate(certs:Seq[X509Certificate]) {
    def areAllContainedIn(expecteds:X509Certificate*):MatchResult[Boolean] = {
      (certs.forall { cert => expecteds.contains(cert) } must beTrue).setMessage(s"$certs !== $expecteds")
    }
  }

  private[this] def dname(cn:String, title:String = "-", ou:String = "Unit Test", o:String = "Carillon", l:String = "Sumida", st:String = "Tokyo", c:String = "JP"):String = {
    s"/C=$c/ST=$st/L=$l/O=$o/OU=$ou/CN=${cn.replace("/", "\\/")}/title=$title"
    // s"CN=${cn.replace("/", "\\/")},OU=$ou,O=$o,L=$l,ST=$st,C=$c" // RFC 2253
  }

}
