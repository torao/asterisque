package io.asterisque

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.{CertPath, X509CRL, X509Certificate}
import java.text.SimpleDateFormat
import java.util.Date

import javax.security.auth.x500.X500Principal
import org.apache.commons.codec.binary.Hex
import play.api.libs.json._

import scala.collection.JavaConverters._

package object carillon {

  case class _Closer[-T](close:T => Unit)

  def using[T, U](resource:T)(f:T => U)(implicit closer:_Closer[T]):U = try {
    f(resource)
  } finally {
    if(resource != null) {
      closer.close(resource)
    }
  }

  implicit val sourceCloser:_Closer[AutoCloseable] = _Closer(_.close())

  private[this] def _hash(binary:Array[Byte]):Array[Byte] = MessageDigest.getInstance("SHA-1").digest(binary)

  private[this] def _fingerprint(binary:Array[Byte]):String = {
    "SHA1:" + _hash(binary).map(i => f"$i%02X").mkString(":")
  }

  private[this] def df(date:Date):String = Option(date).map { t =>
    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS").format(t)
  }.getOrElse("-")

  implicit class _X500Principal(principal:X500Principal) {
    def toJSON:JsValue = Json.obj("name" -> principal.getName)
  }

  implicit class _X509Certificate(cert:X509Certificate) {
    def hash:Array[Byte] = _hash(cert.getEncoded)

    def toByteArray:Array[Byte] = cert.getEncoded

    def fingerprint:String = _fingerprint(cert.getEncoded)

    def toJSON:JsValue = try {
      Json.obj(
        "fingerprint" -> fingerprint,
        "basic-constraints" -> cert.getBasicConstraints,
        "extended-key-usage" -> cert.getExtendedKeyUsage.toJSON(_.toJSON),
        "issuer" -> Json.obj(
          "alternative-names" ->
            cert.getIssuerAlternativeNames.toJSON(_.toJSON(obj => JsString(String.valueOf(obj)))),
          "unique-id" -> JsString(Option(cert.getIssuerUniqueID).map(_.map(b => if(b) "1" else "0").mkString).getOrElse("")),
          "principal" -> cert.getIssuerX500Principal.toJSON
        ),
        "subject" -> Json.obj(
          "alternative-names" ->
            cert.getSubjectAlternativeNames.toJSON(_.toJSON(x => JsString(String.valueOf(x)))),
          "unique-id" -> cert.getSubjectUniqueID.toJSON,
          "principal" -> cert.getSubjectX500Principal.toJSON
        ),
        "key-usage" -> cert.getKeyUsage.toJSON,
        "not-after" -> df(cert.getNotAfter),
        "not-before" -> df(cert.getNotBefore),
        "serial-number" -> cert.getSerialNumber,
        "sig-alg-name" -> cert.getSigAlgName,
        "sig-alg-oid" -> cert.getSigAlgOID,
        "sig-alg-params" -> cert.getSigAlgParams.toJSON,
        "signature" -> cert.getSignature.toJSON,
        "tbs-certificate" -> cert.getTBSCertificate.toJSON,
        "version" -> cert.getVersion,
        "extension-value" -> JsObject((
          Option(cert.getCriticalExtensionOIDs).map(_.asScala).getOrElse(Set.empty) ++
            Option(cert.getNonCriticalExtensionOIDs).map(_.asScala).getOrElse(Set.empty))
          .map { oid =>
            oid -> cert.getExtensionValue(oid).toJSON
          }.toMap),
        "type" -> cert.getType
      )
    } catch {
      case ex:Exception => ex.printStackTrace()
        throw ex
    }
  }

  private[this] implicit class _List[T](list:java.util.Collection[T]) {
    def toJSON(f:T => JsValue):JsArray = JsArray(Option(list).map(_.asScala.map(f).toSeq).getOrElse(Seq.empty))
  }

  private[this] implicit class _StringToJs(str:String) {
    def toJSON:JsString = JsString(str)
  }

  private[this] implicit class _BooleanArrayToJson(b:Array[Boolean]) {
    def toJSON:JsArray = JsArray(Option(b).getOrElse(Array.empty).map(b => JsNumber(if(b) 1 else 0)))
  }

  private[this] implicit class _ByteArrayToJson(b:Array[Byte]) {
    def toJSON:JsString = JsString(Hex.encodeHexString(Option(b).getOrElse(Array.empty)))
  }

  /**
    * 証明書チェーンを人が読める形式にエンコードします。
    */
  implicit class _CertPath(certPath:CertPath) {
    def hash:Array[Byte] = _hash(certPath.getEncoded)

    def fingerprint:String = _fingerprint(certPath.getEncoded)

    def toJSON:JsValue = Json.obj(
      "type" -> certPath.getType,
      "certs" -> certPath.getCertificates.toJSON { cert =>
        cert.asInstanceOf[X509Certificate].toJSON
      }
    )
  }

  implicit class _X509CRL(crl:X509CRL) {
    def hash:Array[Byte] = _hash(crl.getEncoded)

    def fingerprint:String = _fingerprint(crl.getEncoded)

    def toJSON:JsValue = Json.obj(
      "fingerprint" -> fingerprint,
      "issuer" -> Json.obj(
        "principal" -> crl.getIssuerX500Principal.toJSON,
      ),
      "next-update" -> df(crl.getNextUpdate),
      "revoked-certificates" -> crl.getRevokedCertificates.toJSON { entry =>
        Json.obj(
          "certificate-issuer" -> entry.getCertificateIssuer.toJSON,
          "revocation-date" -> df(entry.getRevocationDate),
          "revocation-reason" -> entry.getRevocationReason.toString,
          "serial-number" -> entry.getSerialNumber,
          "extension-value" -> JsObject((crl.getCriticalExtensionOIDs.asScala ++ crl.getNonCriticalExtensionOIDs.asScala)
            .map { oid =>
              oid -> crl.getExtensionValue(oid).toJSON
            }.toMap)
        )
      },
      "sig-alg-name" -> crl.getSigAlgName,
      "sig-alg-oid" -> crl.getSigAlgOID,
      "sig-alg-params" -> crl.getSigAlgParams.toJSON,
      "signature" -> crl.getSignature.toJSON,
      "tbs-cert-list" -> crl.getTBSCertList.toJSON,
      "this-update" -> df(crl.getThisUpdate),
      "version" -> crl.getVersion
    )
  }

  implicit class _ByteBuffer(buf:ByteBuffer) {

    import _ByteBuffer._

    def putTinyString(text:String):ByteBuffer = putTinyBinary(text.getBytes(StandardCharsets.UTF_8))

    def getTinyString:String = new String(getTinyBinary, StandardCharsets.UTF_8)

    def putShortString(text:String):ByteBuffer = putBinary(text.getBytes(StandardCharsets.UTF_8))

    def getShortString:String = new String(getShortBinary, StandardCharsets.UTF_8)

    def putTinyBinary(binary:Array[Byte]):ByteBuffer = putVarBinary(binary, TINY)

    def putShortBinary(binary:Array[Byte]):ByteBuffer = putVarBinary(binary, SHORT)

    def putBinary(binary:Array[Byte]):ByteBuffer = putVarBinary(binary, Int.MaxValue)

    private[this] def putVarBinary(binary:Array[Byte], limit:Int):ByteBuffer = {
      if(binary.length > limit) {
        throw new IllegalArgumentException(s"binary too long: (${binary.length}B > ${limit}B)")
      }
      limit match {
        case TINY => buf.put(binary.length.toByte)
        case SHORT => buf.putShort(binary.length.toShort)
        case _ => buf.putInt(binary.length)
      }
      buf.put(binary)
      buf
    }

    def getTinyBinary:Array[Byte] = getVarBinary(TINY)

    def getShortBinary:Array[Byte] = getVarBinary(SHORT)

    def getBinary:Array[Byte] = getVarBinary(Int.MaxValue)

    private[this] def getVarBinary(limit:Int):Array[Byte] = {
      val length = limit match {
        case TINY => buf.get() & TINY
        case SHORT => buf.getShort() & SHORT
        case _ => buf.getInt()
      }
      val buffer = new Array[Byte](length)
      buf.get(buffer)
      buffer
    }
  }

  object _ByteBuffer {
    val TINY = 0xFF
    val SHORT = 0xFFFF
  }

}
