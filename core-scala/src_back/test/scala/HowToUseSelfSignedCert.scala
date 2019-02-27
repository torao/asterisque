/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
import java.io._
import java.security.KeyStore
import java.util.Date
import javax.net.ssl._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise}
import scala.io.Source

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// HowToUseSelfSignedCert
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * 自己署名証明書を使用したサイトとの TLS を用いたクライアント/サーバ認証のテスト。
 * @author Takami Torao
 */
object HowToUseSelfSignedCert {
  val password = "kazzla".toCharArray

  def main(args:Array[String]):Unit = {
    howToConnectToSelfCertClient()
  }

  def howToConnectToSelfCertClient():Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val caks = KeyStore.getInstance("JKS")
    caks.load(new FileInputStream("ca/cacert.jks"), password)
    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(caks)
    val promise = Promise[Int]()
    scala.concurrent.future {
      // クライアント証明書 (自己署名証明書) の読み込み
      val tks = KeyStore.getInstance("JKS")
      tks.load(new FileInputStream("ca/server.jks"), password)
      val kmf = KeyManagerFactory.getInstance("SunX509")
      kmf.init(tks, password)
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(kmf.getKeyManagers, tmf.getTrustManagers, null)
      // サーバソケット構築
      val server = sslContext.getServerSocketFactory.createServerSocket(8001)
      server.asInstanceOf[SSLServerSocket].setNeedClientAuth(true)
      promise.success(0)
      val peer = server.accept()
      server.close()
      // echo プロトコルの通信開始
      val in = peer.getInputStream
      val out = new OutputStreamWriter(peer.getOutputStream, "UTF-8")
      Source.fromInputStream(in, "UTF-8").getLines().foreach {
        line =>
          out.write(s"$line\r\n")
          out.flush()
      }
      in.close()
      out.close()
      peer.close()
    }
    // サーバが開始するまで待機
    Await.result(promise.future, Duration.Inf)
    // クライアント証明書 (自己署名証明書) の読み込み
    val tks = KeyStore.getInstance("JKS")
    tks.load(new FileInputStream("ca/client.jks"), password)
    val kmf = KeyManagerFactory.getInstance("SunX509")
    kmf.init(tks, password)
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(kmf.getKeyManagers, tmf.getTrustManagers, null)
    val client = sslContext.getSocketFactory.createSocket("localhost", 8001)
    val in = new BufferedReader(new InputStreamReader(client.getInputStream, "UTF-8"))
    val out = new OutputStreamWriter(client.getOutputStream, "UTF-8")
    out.write(s"hello, world\r\n${new Date()}\r\n")
    out.flush()
    System.out.println(s">> ${in.readLine()}")
    System.out.println(s">> ${in.readLine()}")
    in.close()
    out.close()
    client.close()
  }

  def howToConnectToSelfCertServer():Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val promise = Promise[Int]()
    scala.concurrent.future {
      // サーバ証明書 (自己署名証明書) の読み込み
      val tks = KeyStore.getInstance("JKS")
      tks.load(new FileInputStream("ca/server.jks"), password)
      val kmf = KeyManagerFactory.getInstance("SunX509")
      kmf.init(tks, password)
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(kmf.getKeyManagers, null, null)
      // サーバソケット構築
      val server = sslContext.getServerSocketFactory.createServerSocket(8001)
      promise.success(0)
      val peer = server.accept()
      server.close()
      // echo プロトコルの通信開始
      val in = peer.getInputStream
      val out = new OutputStreamWriter(peer.getOutputStream, "UTF-8")
      Source.fromInputStream(in, "UTF-8").getLines().foreach {
        line =>
          out.write(s"$line\r\n")
          out.flush()
      }
      in.close()
      out.close()
      peer.close()
    }
    // サーバが開始するまで待機
    Await.result(promise.future, Duration.Inf)
    val caks = KeyStore.getInstance("JKS")
    caks.load(new FileInputStream("ca/cacert.jks"), password)
    val tmf = TrustManagerFactory.getInstance("SunX509")
    tmf.init(caks)
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, tmf.getTrustManagers, null)
    val client = sslContext.getSocketFactory.createSocket("localhost", 8001)
    val in = new BufferedReader(new InputStreamReader(client.getInputStream, "UTF-8"))
    val out = new OutputStreamWriter(client.getOutputStream, "UTF-8")
    out.write(s"hello, world\r\n${new Date()}\r\n")
    out.flush()
    System.out.println(s">> ${in.readLine()}")
    System.out.println(s">> ${in.readLine()}")
    in.close()
    out.close()
    client.close()
  }

  def inCaseConnectToSelfCertServer():Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val promise = Promise[Int]()
    scala.concurrent.future {
      // サーバ証明書 (自己署名証明書) の読み込み
      val tks = KeyStore.getInstance("JKS")
      tks.load(new FileInputStream("ca/server.jks"), password)
      val kmf = KeyManagerFactory.getInstance("SunX509")
      kmf.init(tks, password)
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(kmf.getKeyManagers, null, null)
      // サーバソケット構築
      val server = sslContext.getServerSocketFactory.createServerSocket(8001)
      promise.success(0)
      val peer = server.accept()
      server.close()
      // echo プロトコルの通信開始
      val in = peer.getInputStream
      val out = new OutputStreamWriter(peer.getOutputStream, "UTF-8")
      Source.fromInputStream(in, "UTF-8").getLines().foreach {
        line =>
          out.write(s"$line\r\n")
          out.flush()
      }
      in.close()
      out.close()
      peer.close()
    }
    // サーバが開始するまで待機
    Await.result(promise.future, Duration.Inf)
    val client = SSLContext.getDefault.getSocketFactory.createSocket("localhost", 8001)
    val in = new BufferedReader(new InputStreamReader(client.getInputStream, "UTF-8"))
    val out = new OutputStreamWriter(client.getOutputStream, "UTF-8")
    out.write(s"hello, world\r\n${new Date()}\r\n")
    out.flush()
    // ↑Exception in thread "main" javax.net.ssl.SSLHandshakeException: sun.security.validator.ValidatorException:
    // PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find
    // valid certification path to requested target
    System.out.println(s">> ${in.readLine()}")
    System.out.println(s">> ${in.readLine()}")
    in.close()
    out.close()
    client.close()
  }

  def howToConnectToSSL():Unit = {
    val client = SSLContext.getDefault.getSocketFactory.createSocket("www.google.com", 443)
    val in = client.getInputStream
    val out = new OutputStreamWriter(client.getOutputStream, "UTF-8")
    out.write("GET / HTTP/1.0\r\nHost: www.google.com\r\nConnection: lock\r\n\r\n")
    out.flush()
    Source.fromInputStream(in, "UTF-8").getLines().foreach {
      line => System.out.println(line)
    }
    in.close()
    out.close()
    client.close()
  }
}
