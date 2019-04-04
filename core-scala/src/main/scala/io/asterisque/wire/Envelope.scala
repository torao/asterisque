package io.asterisque.wire

import java.nio.{ByteBuffer, ByteOrder}
import java.security.cert.X509Certificate
import java.security.{PrivateKey, Signature}
import java.util

import io.asterisque.auth.Algorithms
import io.asterisque.carillon._
import io.asterisque.utils.Debug
import io.asterisque.wire.Envelope.BreakageException
import io.asterisque.wire.rpc.ObjectMapper
import org.msgpack.MessagePack

/**
  * 任意のバイナリデータを電子署名付きで転送するためのデータ構造。
  *
  * @param payload  転送されるデータ
  * @param signType 署名のタイプ
  * @param sign     署名
  * @param signer   署名者の証明書
  */
final case class Envelope(payload:Array[Byte], signType:Envelope.Type, sign:Array[Byte], signer:X509Certificate) {

  /**
    * このインスタンスに含まれているデータが `signer` によって署名されてから改ざんされていないかを検証します。
    *
    * @throws BreakageException in case the seal is broken
    */
  @throws[BreakageException]
  def verify():Unit = {
    val signature = Signature.getInstance(signType.algorithm)
    signature.initVerify(signer)
    signature.update(payload)
    if(signature.verify(sign)) {
      throw new BreakageException(s"invalid signature")
    }
  }

  /**
    * implicit な `Pack` 実装を使用してこのインスタンスのデータからオブジェクトを復元します。
    *
    * @param _pack unpack 実装
    * @tparam T 復元するオブジェクトの型
    * @return 復元したオブジェクト
    */
  def unseal[T]()(implicit _pack:ObjectMapper[T]):T = {
    _pack.decode(new MessagePack().createBufferUnpacker(payload))
  }

  override def toString:String = {
    s"Envelope(${Debug.toString(payload)},$signType,${Debug.toString(sign)},$signer)"
  }

  override def equals(obj:Any):Boolean = obj match {
    case other:Envelope =>
      util.Arrays.equals(this.payload, other.payload) &&
        this.signType.id == other.signType.id &&
        util.Arrays.equals(this.sign, other.sign) &&
        this.signer == other.signer
    case _ => false
  }

  override def hashCode():Int = util.Arrays.hashCode(Array[Int](
    util.Arrays.hashCode(payload),
    signType.id,
    util.Arrays.hashCode(sign),
    util.Arrays.hashCode(signer.getEncoded)
  ))

}

object Envelope {

  /**
    * 指定されたバイナリデータに対して秘密鍵で署名しインスタンスを構築します。
    *
    * @param payload    署名するデータ
    * @param signerCert 署名者の証明書
    * @param signerKey  署名者の秘密鍵
    * @return 署名付き転送データ
    */
  def seal(payload:Array[Byte], signerCert:X509Certificate, signerKey:PrivateKey):Envelope = {
    val signType = Type.SHA512_ECDSA_P1363
    val signature = Signature.getInstance(signType.algorithm)
    signature.initSign(signerKey)
    signature.update(payload)
    val sign = signature.sign()
    Envelope(payload, signType, sign, signerCert)
  }

  /**
    * 指定されたインスタンスに対して秘密鍵で署名しインスタンスを構築します。
    *
    * @param payload    署名するデータ
    * @param signerCert 署名者の証明書
    * @param signerKey  署名者の秘密鍵
    * @param _pack      オブジェクトをバイナリに変換する実装
    * @tparam T オブジェクトの型
    * @return 署名付き転送データ
    */
  def seal[T](payload:T, signerCert:X509Certificate, signerKey:PrivateKey)(implicit _pack:ObjectMapper[T]):Envelope = {
    val packer = new MessagePack().createBufferPacker()
    _pack.encode(packer, payload)
    seal(packer.toByteArray, signerCert, signerKey)
  }

  /**
    * 署名の検証に失敗したときに発生する例外です。
    *
    * @param message 例外メッセージ
    */
  class BreakageException(message:String) extends RuntimeException(message)

  /**
    * どのようなアルゴリズムで署名されているかを表す定数とのマッピングです。
    *
    * @param id        署名識別子
    * @param length    署名の長さ
    * @param algorithm 署名アルゴリズム
    */
  sealed abstract class Type(val id:Byte, val length:Int, val algorithm:String) extends Serializable

  object Type {

    case object SHA512_ECDSA_P1363 extends Type(0, 64, "SHA512withECDSAinP1363FORMAT")

    val values:Seq[Type] = Seq(
      SHA512_ECDSA_P1363
    )

    def valueOf(id:Byte):Option[Type] = values.find(_.id == id)
  }

  /**
    * 指定されたバイトバッファから Seal 形式のバイナリを読み込みます。
    * {{{
    *   [binary-length]:UINT32
    *   [binary]:*
    *   [signature-type]:UINT8
    *   [signature]:*
    *   [signer-cert-length:UINT16
    *   [signer-cert]:*
    * }}}
    *
    * @param buffer 読み込むバイトバッファ
    * @return
    */
  def parse(buffer:ByteBuffer):Envelope = {
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    val payload = buffer.getBinary
    val sigTypeId = buffer.get()
    Type.valueOf(sigTypeId).map { sigType =>
      val sign = new Array[Byte](sigType.length)
      buffer.get(sign)
      val signer = Algorithms.Certificate.load(buffer.getShortBinary)
      new Envelope(payload, sigType, sign, signer)
    }.getOrElse {
      throw new IllegalArgumentException(f"unsupported signature type: 0x${sigTypeId & 0xFF}%02X")
    }
  }
}
