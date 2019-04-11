package io.asterisque.security

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.security.{PrivateKey, Signature, SignatureException}
import java.util.Base64

import io.asterisque.security.Envelope._
import play.api.libs.json._

/**
  * 署名付きの JSON 形式で構造化された値です。複数の署名者を持つことができますが、それぞれの署名は互いに影響しません。署名
  * されていることに対して署名する場合は Envelope をネストさせる必要があります。
  *
  * @param payload JSON 値
  * @param _signs  署名
  */
class Envelope private[Envelope](val payload:JsValue, _signs:Seq[(X509Certificate, Array[Byte], String)]) {
  private[this] lazy val signedPayload = toByteArray(payload)

  /**
    * このデータの署名を参照します。
    */
  lazy val signs:Iterable[Sign] = _signs.map(x => new Sign(x._1, x._2, x._3))

  /**
    * このデータに新しい署名者を追加します。
    *
    * @param key       秘密鍵
    * @param signer    秘密鍵のペアとなる公開鍵証明書
    * @param algorithm 署名アルゴリズム
    * @return 新しい署名を追加したデータ
    */
  def sign(key:PrivateKey, signer:X509Certificate, algorithm:String = DEFAULT_SIGNATURE_ALGORITHM):Envelope = {
    val data = toByteArray(payload)
    val sig = Signature.getInstance(algorithm)
    sig.initSign(key)
    sig.update(data)
    val sign = sig.sign()
    val envelope = new Envelope(payload, _signs :+ ((signer, sign, algorithm)))
    envelope.signs.foreach(_.verify())
    envelope
  }

  /**
    * このデータを JSON 形式で参照します。
    *
    * @return データの JSON 形式
    */
  def toJSON:JsObject = Json.obj(
    "signs" -> signs.map(_.toJSON),
    "payload" -> payload
  )

  class Sign private[Envelope](val signer:X509Certificate, signature:Array[Byte], algorithm:String) {
    @throws[SignatureException]
    def verify():Unit = {
      val sig = Signature.getInstance(algorithm)
      sig.initVerify(signer)
      sig.update(signedPayload)
      if(!sig.verify(signature)) {
        throw new SignatureException(s"signature doesn't match")
      }
    }

    def toJSON:JsObject = Json.obj(
      "signer" -> Base64.getEncoder.encodeToString(signer.getEncoded),
      "algorithm" -> algorithm,
      "signature" -> Base64.getEncoder.encodeToString(signature)
    )
  }

}

object Envelope {

  val DEFAULT_SIGNATURE_ALGORITHM = "SHA512withECDSAinP1363FORMAT"

  def apply(payload:JsValue, key:PrivateKey, signer:X509Certificate, algorithm:String = DEFAULT_SIGNATURE_ALGORITHM):Envelope = {
    new Envelope(payload, Seq.empty).sign(key, signer, algorithm)
  }

  @throws[IllegalArgumentException]
  def parse(json:JsValue):Envelope = json match {
    case JsObject(envelope) =>
      val signs = envelope.get("signs") match {
        case Some(JsArray(_signs)) =>
          _signs.map {
            case JsObject(sign) =>
              (sign.get("signer"), sign.get("algorithm"), sign.get("signature")) match {
                case (Some(signer:JsString), Some(algorithm:JsString), Some(signature:JsString)) =>
                  val encoded = Base64.getDecoder.decode(signer.value)
                  val cert = Algorithms.X509Factory.generateCertificate(new ByteArrayInputStream(encoded))
                  (cert.asInstanceOf[X509Certificate], Base64.getDecoder.decode(signature.value), algorithm.value)
                case _ =>
                  val msg = s"signer, algorithm or signature is not specified: ${Json.stringify(json)}"
                  throw new IllegalArgumentException(msg)
              }
            case _ =>
              val msg = s"signs contains unexpected element: ${Json.stringify(json)}"
              throw new IllegalArgumentException(msg)
          }
        case _ =>
          val msg = s"signs is not specified or not JSON Array: ${Json.stringify(json)}"
          throw new IllegalArgumentException(msg)
      }
      val payload = envelope.getOrElse("payload", {
        val msg = s"payload is not specified: ${Json.stringify(json)}"
        throw new IllegalArgumentException(msg)
      })
      new Envelope(payload, signs)
    case _ =>
      val msg = s"specified parameter is not JSON Object: ${Json.stringify(json)}"
      throw new IllegalArgumentException(msg)
  }

  private[Envelope] def toByteArray(json:JsValue):Array[Byte] = {
    // TODO 同じ JSON に対して同じバイナリとなるように保証するための変換仕様を決めて実装する
    Json.asciiStringify(json).getBytes(StandardCharsets.US_ASCII)
  }
}