package io.asterisque.wire.gateway.netty

import java.net.{Socket, URI}
import java.security.cert.X509Certificate
import java.security.{Principal, PrivateKey, Security}
import java.util.concurrent.TimeUnit

import io.asterisque.test._
import io.asterisque.wire.gateway.{Bridge, Wire}
import io.asterisque.wire.message.Message
import javax.net.ssl._
import org.slf4j.LoggerFactory
import org.specs2.Specification
import org.specs2.execute.Result

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise}

class NettyBridgeSpec extends Specification {
  override def is =
    s2"""
Simple echo communication. $simpleClientServer
"""

  private[this] val logger = LoggerFactory.getLogger(classOf[NettyBridgeSpec])

  protected def availableBridges:Seq[Bridge] = Seq(new NettyBridge())

  private[this] def simpleClientServer = {
    logger.info(Security.getAlgorithms("SSLContext").asScala.mkString("[", ", ", "]"))

    val (privateKey, certificate) = NODE_CERTS.head
    val keyManager = new X509ExtendedKeyManager {
      override def getClientAliases(keyType:String, issuers:Array[Principal]):Array[String] = {
        logger.debug(s"getClientAliases($keyType, $issuers)")
        Array("foo")
      }

      override def chooseClientAlias(keyType:Array[String], issuers:Array[Principal], socket:Socket):String = {
        logger.debug(s"chooseClientAlias($keyType, $issuers, $socket)")
        "foo"
      }

      override def getServerAliases(keyType:String, issuers:Array[Principal]):Array[String] = {
        logger.debug(s"getServerAliases($keyType, $issuers)")
        Array("bar")
      }

      override def chooseServerAlias(keyType:String, issuers:Array[Principal], socket:Socket):String = {
        logger.debug(s"chooseServerAlias($keyType, $issuers, $socket)")
        "bar"
      }

      override def getCertificateChain(alias:String):Array[X509Certificate] = Array(certificate)

      override def getPrivateKey(alias:String):PrivateKey = privateKey
    }

    val trustManager = new X509ExtendedTrustManager {
      override def checkClientTrusted(chain:Array[X509Certificate], authType:String, socket:Socket):Unit = None

      override def checkServerTrusted(chain:Array[X509Certificate], authType:String, socket:Socket):Unit = None

      override def checkClientTrusted(chain:Array[X509Certificate], authType:String, engine:SSLEngine):Unit = None

      override def checkServerTrusted(chain:Array[X509Certificate], authType:String, engine:SSLEngine):Unit = None

      override def checkClientTrusted(chain:Array[X509Certificate], authType:String):Unit = None

      override def checkServerTrusted(chain:Array[X509Certificate], authType:String):Unit = None

      override def getAcceptedIssuers:Array[X509Certificate] = Array(certificate)
    }

    //    val sslContext = SSLContext.getInstance("TLS")
    //    sslContext.init(Array(keyManager), Array(trustManager), new SecureRandom())
    val sslContext = SSLContext.getDefault

    def _echo(wire:Wire):Result = {
      wire.outbound.offer(Message.Open(0, 0, Array.empty))
      wire.inbound.take() match {
        case _:Message.Open =>
          wire.outbound.offer(Message.Close(0, 0, Array.empty))
          wire.inbound.take() match {
            case _:Message.Close => success
            case msg => failure(s"Close expect but: $msg")
          }
        case msg => failure(s"Open expect but: $msg")
      }
    }

    val SEC30 = Duration(30, TimeUnit.SECONDS)

    val promise = Promise[Wire]()
    val server = {
      val uri = new URI("wss://0.0.0.0:0/asterisque")
      val future = Bridge.builder()
        .sslContext(sslContext)
        .newServer(uri, promise.completeWith)
      Await.result(future, SEC30)
    }
    logger.info(s"server address: ${server.acceptURI}")

    val wire = {
      val uri = new URI(s"wss://localhost:${server.acceptURI.getPort}/asterisque")
      val future = Bridge.builder().sslContext(sslContext).newWire(uri)
      Await.result(future, SEC30)
    }
    logger.info(s"client: ${wire.local} <--> ${wire.remote}")

    val clientResult = _echo(wire)
    val serverResult = Await.result(promise.future.map(_echo), SEC30)

    wire.close()
    server.close()
    clientResult and serverResult
  }

}
