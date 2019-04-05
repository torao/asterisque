package io.asterisque.security

import java.io.File
import java.security.cert.{CertPath, X509Certificate}

import io.asterisque.test._
import io.asterisque.tools.PKI
import org.specs2.Specification
import org.specs2.matcher.Matcher

import scala.collection.JavaConverters._
import scala.util.Try

class TrustContextSpec extends Specification {

  def is =
    s2"""
Success on the directory that is empty or not exist. $skipped
$testLoad
      """

  private[this] def constructWithEmptyOrNotExistDir = fs.temp(this) { dir =>
    val empty = TrustContext(dir, "****")
    val notExist = TrustContext(new File(dir, "not-exist"), "****")
    (empty.getKeyManagers.isEmpty must beTrue) and
      (empty.getTrustManagers.nonEmpty must beTrue) and
      (notExist.getKeyManagers.isEmpty must beTrue) and
      (notExist.getTrustManagers.nonEmpty must beTrue)
  }

  private[this] def testLoad = fs.temp(this, false) { dir =>
    val root = PKI.CA.newRootCA(new File(dir, "root"), dname("root.ca"))
    val ca1 = PKI.CA.newIntermediateCA(root, new File(dir, "ca1"), dname("1.ca"))
    val ca2 = PKI.CA.newIntermediateCA(ca1, new File(dir, "ca2"), dname("2.ca"))
    val caA = PKI.CA.newIntermediateCA(root, new File(dir, "caA"), dname("a.ca"))
    val caB = PKI.CA.newIntermediateCA(root, new File(dir, "caB"), dname("b.ca"))

    val node1 = TrustContext.newTrustContext(new File(dir, "node1"), ca2, "****", dname("node1"))
    node1.deployTrustedCA(caA.certPathFile)

    val node2 = TrustContext.newTrustContext(new File(dir, "node2"), caA, "****", dname("node2"))

    val node3 = TrustContext.newTrustContext(new File(dir, "node3"), caB, "****", dname("node3"))

    val blocked1 = TrustContext.newTrustContext(new File(dir, "blocked1"), caA, "****", dname("blocked1"))
    node1.deployBlocked(blocked1.getCertPaths.values.head.getCertificates.asScala.head.asInstanceOf[X509Certificate])

    def verify(node:TrustContext, certs:Seq[CertPath], result:Matcher[Boolean]) = {
      certs.map(certPath => Try(node1.verify(certPath)).isSuccess must result).reduceLeft(_ and _)
    }

    verify(node1, node1.getCertPaths.values.toSeq, beTrue) and
      verify(node1, node2.getCertPaths.values.toSeq, beTrue) and
      verify(node1, node3.getCertPaths.values.toSeq, beFalse) and
      verify(node1, blocked1.getCertPaths.values.toSeq, beFalse)
  }

  private[this] def dname(cn:String):String = s"/C=JP/ST=Tokyo/L=Sumida/O=asterisque/CN=$cn.asterisque.io"
}
