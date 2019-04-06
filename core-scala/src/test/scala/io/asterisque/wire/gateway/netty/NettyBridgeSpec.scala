package io.asterisque.wire.gateway.netty

import java.io.File
import java.net.URI
import java.security.Security
import java.util.concurrent.TimeUnit

import io.asterisque.security.TrustContext
import io.asterisque.test._
import io.asterisque.tools.PKI
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

  private[this] def simpleClientServer = fs.temp(this) { dir =>
    logger.info(Security.getAlgorithms("SSLContext").asScala.mkString("[", ", ", "]"))

    val ca = PKI.CA.newRootCA(new File(dir, "ca"), dn("ca"))
    val peer1 = TrustContext.newTrustContext(new File(dir, "peer1"), ca, "foo", "****", dn("peer1"))
    val peer2 = TrustContext.newTrustContext(new File(dir, "peer2"), ca, "foo", "****", dn("peer2"))

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
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(peer1.getKeyManagers, peer1.getTrustManagers, null)
      val future = Bridge.builder()
        .sslContext(sslContext)
        .newServer(uri, promise.completeWith)
      Await.result(future, SEC30)
    }
    logger.info(s"server address: ${server.acceptURI}")

    val wire = {
      val uri = new URI(s"wss://localhost:${server.acceptURI.getPort}/asterisque")
      val sslContext = SSLContext.getInstance("TLS")
      sslContext.init(peer2.getKeyManagers, peer2.getTrustManagers, null)
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
