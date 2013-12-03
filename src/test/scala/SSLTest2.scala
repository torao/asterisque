/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
import java.io._
import java.net.Socket
import java.security.KeyStore
import java.util.Date
import javax.net.ssl._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise}
import scala.io.Source

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// SSLTest2
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * 自己署名証明書を使用したサイトとの TLS を用いたクライアント/サーバ認証のテスト。
 * @author Takami Torao
 */
object SSLTest2 {
	val password = "kazzla".toCharArray
	val port = 8001

	/**
	 * 指定されたパスから証明書をロードし SSLContext を構築します。
	 * ファイルは keytool で作成された JKS 形式です。
	 */
	def cert(path:String):SSLContext = {
		val tks = KeyStore.getInstance("JKS")
		tks.load(new FileInputStream(path), password)
		val caks = KeyStore.getInstance("JKS")
		caks.load(new FileInputStream("ca/cacert.jks"), password)
		val kmf = KeyManagerFactory.getInstance("SunX509")
		kmf.init(tks, password)
		val sslContext = SSLContext.getInstance("TLS")
		val tmf = TrustManagerFactory.getInstance("SunX509")
		tmf.init(caks)
		// sslContext.init(kmf.getKeyManagers, tmf.getTrustManagers, null)
		sslContext.init(kmf.getKeyManagers, null, null)
		sslContext
	}

	def main(args:Array[String]):Unit = {

		// サーバ用のスレッドを開始
		val server = cert("ca/server.jks").getServerSocketFactory.createServerSocket(port) match {
			case s:SSLServerSocket =>
				s.setUseClientMode(false)
				s
			case s => s
		}
		new Thread(){
			override def run(){
				while(! server.isClosed){
					serve(server.accept())
				}
			}
		}.start()

		// クライアントから接続
		val client = cert("ca/client.jks").getSocketFactory.createSocket("localhost", port)
		val out = new PrintWriter(client.getOutputStream)
		out.println("hello world\nI'm echo client")
		out.flush()
		try {
			Source.fromInputStream(client.getInputStream).getLines().foreach{ line =>
				System.out.println(">>" + line)
			}
			client.getInputStream.close()
			out.close()
		} catch {
			case ex:Exception =>
				System.out.println(s"CLIENT: $ex")
			ex.printStackTrace()
		}
		client.close()

		// サーバ終了
		server.close()
	}

	def serve(client:Socket):Unit = try {
		val in = client.getInputStream
		val out = client.getOutputStream
		Source.fromInputStream(in).foreach { ch =>
			out.write(ch)
		}
		in.close()
		out.close()
		client.close()
	} catch {
		case ex:Exception =>
			System.out.println(s"SERVER: $ex")
		ex.printStackTrace()
	}
}
