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

class BlockedCertsStoreSpec extends Specification {
  def is =
    s2"""
It can load trusted X.509 certificates from local. $installCRL
    """

  private[this] def installCRL = fs.temp(this) { root =>
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

  implicit class _SeqX509Certificate(certs:Seq[X509Certificate]) {
    def areAllContainedIn(expecteds:X509Certificate*):MatchResult[Boolean] = {
      (certs.forall { cert => expecteds.contains(cert) } must beTrue).setMessage(s"$certs !== $expecteds")
    }
  }

  private[this] def dname(cn:String, ou:String = "Unit Test", o:String = "Carillon", l:String = "Sumida", st:String = "Tokyo", c:String = "JP"):String = {
    s"/C=$c/ST=$st/L=$l/O=$o/OU=$ou/CN=$cn"
  }

}
