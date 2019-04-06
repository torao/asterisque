package io.asterisque.security

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, NoSuchFileException}
import java.security.cert.{CertPath, CertificateException, X509Certificate}
import java.util.concurrent.{Executors, TimeUnit}

import io.asterisque.security.TrustContext._
import io.asterisque.test._
import io.asterisque.tools.PKI
import io.asterisque.utils.IO
import javax.net.ssl.{SSLContext, SSLException, SSLServerSocket, SSLSocket}
import org.specs2.Specification
import org.specs2.matcher.Matcher

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.Try

class TrustContextSpec extends Specification {

  def is =
    s2"""
Success on the directory that is empty or not exist. $constructWithEmptyOrNotExistDir
It can verify certificate with trusted CA. $normalVerification
It raise exception when deploy failure. $deployFailure
It can used to communicate with TLS. $tls
It skips large certificate file. $skipLargeFile
"""

  private[this] val pass = "****"

  private[this] def constructWithEmptyOrNotExistDir = fs.temp(this) { dir =>
    val empty = TrustContext(dir, "foo", pass)
    val notExist = TrustContext(new File(dir, "not-exist"), "foo", pass)
    (empty.getKeyManagers.isEmpty must beTrue) and
      (empty.getTrustManagers.nonEmpty must beTrue) and
      (notExist.getKeyManagers.isEmpty must beTrue) and
      (notExist.getTrustManagers.nonEmpty must beTrue)
  }

  private[this] def normalVerification = fs.temp(this) { dir =>
    val alias = "unit-test"
    val root = PKI.CA.newRootCA(new File(dir, "root"), dname("root.ca"))
    val ca1 = PKI.CA.newIntermediateCA(root, new File(dir, "ca1"), dname("1.ca"))
    val ca2 = PKI.CA.newIntermediateCA(ca1, new File(dir, "ca2"), dname("2.ca"))
    val caA = PKI.CA.newIntermediateCA(root, new File(dir, "caA"), dname("a.ca"))
    val caB = PKI.CA.newIntermediateCA(root, new File(dir, "caB"), dname("b.ca"))
    val caC = PKI.CA.newIntermediateCA(root, new File(dir, "caC"), dname("c.ca"))
    val caD = PKI.CA.newIntermediateCA(caC, new File(dir, "caD"), dname("d.ca"))

    val target = newTrustContext(new File(dir, "target"), ca2, alias, pass, dname("target"))
    target.deployTrustedCA(caA.certPathFile)

    val trusted = newTrustContext(new File(dir, "trusted"), caA, alias, pass, dname("trusted"))

    val untrusted = newTrustContext(new File(dir, "untrusted"), caB, alias, pass, dname("untrusted"))

    // 証明書がブロックされている
    val blocked1 = newTrustContext(new File(dir, "blocked1"), caA, alias, pass, dname("blocked1"))
    target.deployBlocked(blocked1.getCertPath.getCertificates.asScala.head.asInstanceOf[X509Certificate])

    // 証明書を発行した CA の親 CA がブロックされている
    val blocked2 = newTrustContext(new File(dir, "blocked2"), caD, alias, pass, dname("blocked2"))
    target.deployTrustedCA(caD.certPathFile)
    target.deployBlocked(caC.certPathFile)

    def verify(node:TrustContext, certPath:CertPath, result:Matcher[Boolean]) = {
      val x = Try(target.verify(certPath))
      (x.isSuccess must result).setMessage(x.toString)
    }

    verify(target, target.getCertPath, beTrue) and
      verify(target, trusted.getCertPath, beTrue) and
      verify(target, untrusted.getCertPath, beFalse) and
      verify(target, blocked1.getCertPath, beFalse) and
      verify(target, blocked2.getCertPath, beFalse)
  }

  private[this] def deployFailure = fs.temp(this) { dir =>
    import java.nio.file.StandardOpenOption._
    val ca = PKI.CA.newRootCA(new File(dir, "ca"), dname("ca"))
    val user = newTrustContext(new File(dir, "user"), ca, "foo", pass, dname("user"))

    val random = new File(dir, "random")
    Files.write(random.toPath, randomByteArray(457392, 64 * 1024), CREATE_NEW, WRITE)

    (user.deployTrustedCA(new File(dir, "notexist")) must throwA[NoSuchFileException]) and
      (user.deployBlocked(new File(dir, "notexist")) must throwA[NoSuchFileException]) and
      (user.deployTrustedCA(random) must throwA[CertificateException]) and
      (user.deployBlocked(random) must throwA[CertificateException])
  }

  /**
    * TrustContext を使った信頼関係に基づく TLS 通信が想定通りの挙動かのテスト。
    */
  private[this] def tls = fs.temp(this) { dir =>
    val ca1 = PKI.CA.newRootCA(new File(dir, "ca1"), dname("ca1"))
    val ca2 = PKI.CA.newRootCA(new File(dir, "ca2"), dname("ca2"))

    val me = newTrustContext(new File(dir, "me"), ca1, "foo", pass, dname("me"))

    // 同じ CA から認可されたノード
    val sibling = newTrustContext(new File(dir, "sibling"), ca1, "foo", pass, dname("sibling"))

    // 同じ CA から認可されているがブロックされているノード
    val disowning = newTrustContext(new File(dir, "disowning"), ca1, "foo", pass, dname("disowning"))
    me.deployBlocked(disowning.getCertPath.getCertificates.asScala.head.asInstanceOf[X509Certificate])

    // 異なる CA から認可されたノード
    val alien = newTrustContext(new File(dir, "alien"), ca2, "foo", pass, dname("alien"))

    // 異なる CA から認可されているが信頼済み CA に登録したノード
    val friend = newTrustContext(new File(dir, "friend"), ca2, "foo", pass, dname("friend"))
    friend.deployTrustedCA(ca1.certPathFile)

    {
      // 同一の CA から認可されたノードはクライアント認証/サーバ認証ともに TLS 通信が可能
      communicateByTLS(me, sibling, clientAuth = true) and
        communicateByTLS(me, sibling, clientAuth = false)
    } and {
      // 同一の CA から認可されたノードでもサーバからブロックされていればクライアント認証に失敗するが、サーバ認証なら成功
      (communicateByTLS(me, disowning, clientAuth = true) must throwA[SSLException]) and
        communicateByTLS(me, disowning, clientAuth = false)
    } and {
      // お互いに信頼済み CA を共有していないノードは通信不可能
      (communicateByTLS(me, alien, clientAuth = true) must throwA[SSLException]) and
        (communicateByTLS(me, alien, clientAuth = false) must throwA[SSLException])
    } and {
      // クライアントがサーバの CA を信頼済みに設定していればサーバ認証は可能
      (communicateByTLS(me, friend, clientAuth = true) must throwA[SSLException]) and
        communicateByTLS(me, friend, clientAuth = false)
    }
  }

  /**
    * 指定された TrustContext を使った TLS 通信が可能かを検証する。
    */
  private[this] def communicateByTLS(server:TrustContext, client:TrustContext, clientAuth:Boolean) = {
    val context1 = SSLContext.getInstance("TLS")
    context1.init(server.getKeyManagers, server.getTrustManagers, null)
    val serverSocketFactory = context1.getServerSocketFactory

    val context2 = SSLContext.getInstance("TLS")
    context2.init(client.getKeyManagers, client.getTrustManagers, null)
    val socketFactory = context2.getSocketFactory

    val threads = Executors.newCachedThreadPool()
    try {
      implicit val _context:ExecutionContextExecutor = ExecutionContext.fromExecutor(threads)

      val serverSocket = serverSocketFactory.createServerSocket(0).asInstanceOf[SSLServerSocket]
      serverSocket.setNeedClientAuth(true)
      val serverResult = Future {
        val socket = serverSocket.accept().asInstanceOf[SSLSocket]
        socket.setNeedClientAuth(clientAuth)
        socket.setUseClientMode(false)
        serverSocket.close()
        val in = new BufferedReader(new InputStreamReader(socket.getInputStream))
        val out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream))
        out.println(in.readLine().reverse)
        out.flush()
        socket.close()
      }

      val port = serverSocket.getLocalPort
      val clientSocket = socketFactory.createSocket("localhost", port).asInstanceOf[SSLSocket]
      clientSocket.setNeedClientAuth(clientAuth)
      clientSocket.setUseClientMode(true)
      val in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream))
      val out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream))
      out.println("hello, world")
      out.flush()
      val echoback = in.readLine()
      clientSocket.close()

      Await.result(serverResult, Duration.apply(30, TimeUnit.SECONDS))

      echoback === "hello, world".reverse
    } finally {
      threads.shutdown()
    }
  }

  private[this] def skipLargeFile = fs.temp(this) {
    dir =>
      val ca1 = PKI.CA.newRootCA(new File(dir, "ca1"), dname("ca1"))
      val node1 = newTrustContext(new File(dir, "node1"), ca1, "foo", pass, dname("node1"))

      val ca2 = PKI.CA.newRootCA(new File(dir, "ca2"), dname("ca2"))
      val node2 = newTrustContext(new File(dir, "node2"), ca2, "foo", pass, dname("node2"))

      val incompatible = node1.verify(node2.getCertPath) must throwA[CertificateException]

      val largeFile = new File(dir, "large.pem")
      IO.copy(ca2.certPathFile, largeFile)
      IO.using(new FileOutputStream(largeFile, true)) {
        out =>
          val padding = randomASCII(48058, MAX_CERT_SIZE_TO_READ.toInt)
          out.write(padding.getBytes(StandardCharsets.US_ASCII))
      }
      node1.deployTrustedCA(largeFile)
      val ignored = node1.verify(node2.getCertPath) must throwA[CertificateException]

      node1.deployTrustedCA(ca2.certPathFile)
      val authorized = {
        node1.verify(node2.getCertPath)
        success
      }

      incompatible and ignored and authorized
  }

  private[this] def dname(cn:String):String = s"/C=JP/ST=Tokyo/L=Sumida/O=asterisque/CN=$cn.asterisque.io"
}
