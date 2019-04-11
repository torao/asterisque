package io.asterisque.security

import java.io._
import java.nio.file.Files
import java.nio.file.StandardOpenOption._
import java.security.cert.X509Certificate
import java.security.{PrivateKey, SignatureException}
import java.util.Random

import io.asterisque.test._
import io.asterisque.tools.PKI
import org.slf4j.LoggerFactory
import org.specs2.Specification
import play.api.libs.json._

class EnvelopeSpec extends Specification {

  def is =
    s2"""
It can store and load envelope for the random JSON. $storeAndLoad
It throws SignatureException when data tampered. $dataTampering
It throws IllegalArgumentException for invalid JSON structure. $parseErrorPatterns
"""

  private[this] val logger = LoggerFactory.getLogger(classOf[EnvelopeSpec])

  private[this] def storeAndLoad = fs.temp(this) { dir =>
    val ca = PKI.CA.newRootCA(new File(dir, "ca"), dn("ca"))
    val ksFile = new File(dir, "keystore.p12")
    ca.newPKCS12KeyStore(ksFile, "foo", "****", dn("node"))
    val ks = Algorithms.KeyStore.load(ksFile, "****".toCharArray).get
    val key = ks.getKey("foo", "****".toCharArray).asInstanceOf[PrivateKey]
    val cert = ks.getCertificate("foo").asInstanceOf[X509Certificate]

    (0 until 20).map(i => randomJSON(328475 + i)).zipWithIndex.map { case (json, i) =>
      logger.info(f"$i%02d: ${Json.stringify(json)}%s")
      val envelope = Envelope(json, key, cert)
      val file = new File(dir, s"$i.json")
      Files.writeString(file.toPath, Json.prettyPrint(envelope.toJSON), CREATE_NEW, WRITE)
      val loaded = Envelope.parse(Json.parse(Files.readString(file.toPath)))
      loaded.signs.foreach(_.verify())
      loaded.payload === envelope.payload
    }
  }

  private[this] def dataTampering = fs.temp(this) { dir =>
    val ca = PKI.CA.newRootCA(new File(dir, "ca"), dn("ca"))
    val ksFile = new File(dir, "keystore.p12")
    ca.newPKCS12KeyStore(ksFile, "foo", "****", dn("node"))
    val ks = Algorithms.KeyStore.load(ksFile, "****".toCharArray).get
    val key = ks.getKey("foo", "****".toCharArray).asInstanceOf[PrivateKey]
    val cert = ks.getCertificate("foo").asInstanceOf[X509Certificate]

    val json = JsString("0123456789")
    val envelope = Envelope(json, key, cert)
    val e2 = Envelope.parse(Json.parse(Json.stringify(envelope.toJSON).replace("23456", "23X56")))
    e2.signs.foreach(_.verify()) must throwA[SignatureException]
  }

  private[this] def parseErrorPatterns = fs.temp(this) { dir =>
    val ca = PKI.CA.newRootCA(new File(dir, "ca"), dn("ca"))
    val ksFile = new File(dir, "keystore.p12")
    ca.newPKCS12KeyStore(ksFile, "foo", "****", dn("node"))
    val ks = Algorithms.KeyStore.load(ksFile, "****".toCharArray).get
    val key = ks.getKey("foo", "****".toCharArray).asInstanceOf[PrivateKey]
    val cert = ks.getCertificate("foo").asInstanceOf[X509Certificate]

    val js = (Envelope(JsString("foo"), key, cert).toJSON \ "signs").apply(0)
    val signer = (js \ "signer").as[JsString]
    val algorithm = (js \ "algorithm").as[JsString]
    val signature = (js \ "signature").as[JsString]

    Seq(
      JsString("boo"),
      Json.obj("foo" -> "bar"),
      Json.obj("signs" -> "bar"),
      Json.obj("signs" -> Seq(JsNumber(Math.PI))),
      Json.obj("signs" -> Seq(Map("algorithm" -> algorithm, "signature" -> signature))),
      Json.obj("signs" -> Seq(Map("signer" -> signer, "signature" -> signature))),
      Json.obj("signs" -> Seq(Map("signer" -> signer, "algorithm" -> algorithm))),
      Json.obj("signs" -> Seq(Map("signer" -> signer, "algorithm" -> algorithm, "signature" -> signature)))
    ).map { json =>
      (Envelope.parse(json) must throwA[IllegalArgumentException]).setMessage(Json.stringify(json))
    }.reduceLeft(_ and _)
  }

  private[this] def randomJSON(seed:Long):JsValue = {
    val random = new Random(seed)
    random.nextDouble() // to avoid biased-random number of Java standard library

    def newNode(depth:Int = 0):JsValue = random.nextDouble() match {
      case r if r < 0.20 => JsString(randomString(random.nextInt, random.nextInt(256)))
      case r if r < 0.40 => JsNumber(random.nextLong())
      case r if r < 0.60 => JsBoolean(random.nextBoolean())
      case r if r < 0.80 || depth > 16 => JsNull
      case r if r < 0.90 =>
        JsArray((0 until random.nextInt(16)).map(_ => newNode(depth + 1)))
      case _ =>
        JsObject((0 until random.nextInt(16))
          .map(_ => (randomASCII(random.nextInt(), random.nextInt(20)), newNode(depth + 1))))
    }

    newNode()
  }
}
