package io.asterisque.utils

import java.lang.reflect.{Constructor, Method}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.{CertPath, Certificate, X509Certificate}
import java.text.{DateFormat, SimpleDateFormat}
import java.util._
import java.util.stream.Collectors

import javax.annotation.{Nonnull, Nullable}
import javax.net.ssl.{SSLException, SSLPeerUnverifiedException, SSLSession}
import org.slf4j.Logger

import scala.collection.JavaConverters._

/**
  * デバッグ用のユーティリティ機能です。
  *
  * @author Takami Torao
  */
object Debug {

  /**
    * 指定されたインスタンスをデバッグ用に人間が読みやすい形式に変換します。
    *
    * @param value 文字列化するオブジェクト
    * @return デバッグ用の文字列
    */
  @Nonnull
  def toString(@Nullable value:Any):String = {
    val buf = new java.lang.StringBuilder()
    _str(Seq.empty, buf, value)
    buf.toString
  }

  @Nonnull
  private[this] def _str(stack:Seq[Any], buf:Appendable, @Nullable value:Any):Appendable = {
    if(value != null && stack.contains(value)) {
      buf.append(s"CIRCULAR_REFERENCE[$value]")
    } else value match {
      case null => buf.append("null")
      case ch:Char => buf.append(s"'${escape(ch)}'")
      case str:String =>
        val escaped = str.chars.mapToObj((ch:Int) => Debug.escape(ch.toChar)).collect(Collectors.joining)
        buf.append(s""""$escaped"""")
      case date:java.util.Date =>
        buf.append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS").format(date))
      case addr:InetSocketAddress =>
        buf.append(s"${addr.getHostName}:${addr.getPort}")
      case addr:InetAddress =>
        buf.append(s"${addr.getHostName}/${addr.getHostAddress}")
      case cert:X509Certificate =>
        buf.append(toString(cert.getSubjectX500Principal.getName))
      case certs:CertPath =>
        buf.append(toString(certs.getCertificates.asScala))
      case ks:KeyStore =>
        buf.append(s"KeyStore[${ks.getType}:${ks.aliases().asScala.mkString(",")}]")
      case opt:Optional[_] =>
        if(opt.isPresent) {
          buf.append("Some(")
          _str(stack :+ value, buf, opt.get)
          buf.append(")")
        } else {
          buf.append("None")
        }
      case Some(elem) =>
        buf.append("Some")
        _str(stack :+ value, buf, elem)
        buf.append(")")
      case map:java.util.Map[_, _] => _str(stack, buf, map.asScala)
      case col:java.util.Collection[_] => _str(stack, buf, col.asScala.toSeq)
      case map:scala.collection.Map[_, _] =>
        buf.append("{")
        map.zipWithIndex.foreach { case ((k, v), i) =>
          if(i != 0) buf.append(",")
          _str(stack :+ value, buf, k)
          buf.append(":")
          _str(stack :+ value, buf, v)
        }
        buf.append("}")
      case list:Seq[_] =>
        buf.append("[")
        list.zipWithIndex.foreach { case (v, i) =>
          if(i != 0) buf.append(",")
          _str(stack :+ value, buf, v)
        }
        buf.append("]")
      case bin:Array[Byte] =>
        bin.foreach { b =>
          buf.append(f"${b & 0xFF}%02X")
        }
        buf
      case cs:Array[Char] =>
        buf.append(new String(cs))
      case arr:Array[AnyRef] =>
        _str(stack, buf, arr.toSeq)
      case b:ByteBuffer =>
        val bin = new Array[Byte](b.remaining)
        val pos = b.position()
        b.get(bin, 0, bin.length)
        b.position(pos)
        _str(stack, buf, bin)
      case obj => buf.append(obj.toString)
    }
  }

  /**
    * 指定された文字を Java の文字列リテラルとして使用できるようにエスケープします。
    *
    * @param ch エスケープする文字
    * @return エスケープされた文字
    */
  def escape(ch:Char):String = ch match {
    case '\b' => "\\b"
    case '\f' => "\\f"
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case '\\' => "\\\\"
    case '\'' => "\\\'"
    case '\"' => "\\\""
    case control if !Character.isDefined(control) || Character.isISOControl(control) => f"\\u${control & 0xFFFF}%04X"
    case opaque => opaque.toString
  }

  /**
    * 指定されたメソッドを人が判別できるシンプルな文字列に変換します。
    *
    * @param m 文字列化するメソッド
    * @return メソッドを識別する文字列
    */
  def getSimpleName(m:Method):String = s"${m.getDeclaringClass.getSimpleName}.${m.getName}(${m.getParameterTypes.map(_.getSimpleName).mkString(",")}):${m.getReturnType.getSimpleName}"

  def getSimpleName(m:Constructor[_]):String = s"${m.getDeclaringClass.getSimpleName}.${m.getName}(${m.getParameterTypes.map(_.getSimpleName).mkString(",")}"

  def dumpCertificate(logger:Logger, prefix:String, cs:Certificate):Unit = if(logger.isTraceEnabled) {
    cs match {
      case c:X509Certificate =>
        val df = DateFormat.getDateTimeInstance
        logger.trace(String.format("%s: Serial Number: %s", prefix, c.getSerialNumber))
        logger.trace(String.format("%s: Signature Algorithm: %s", prefix, c.getSigAlgName))
        logger.trace(String.format("%s: Signature Algorithm OID: %s", prefix, c.getSigAlgOID))
        logger.trace(String.format("%s: Issuer Principal Name: %s", prefix, c.getIssuerX500Principal.getName))
        logger.trace(String.format("%s: Subject Principal Name: %s", prefix, c.getSubjectX500Principal.getName))
        logger.trace(String.format("%s: Expires: %s - %s", prefix, df.format(c.getNotBefore), df.format(c.getNotAfter)))
      case _ =>
        logger.trace(String.format("%s: Type: %s", prefix, cs.getType))
        logger.trace(String.format("%s: Public Key Algorithm: %s", prefix, cs.getPublicKey.getAlgorithm))
        logger.trace(String.format("%s: Public Key Format: %s", prefix, cs.getPublicKey.getFormat))
    }
  }

  @throws[SSLPeerUnverifiedException]
  def dumpSSLSession(logger:Logger, prefix:String, session:SSLSession):Unit = try {
    if(logger.isTraceEnabled) {
      logger.trace(String.format("%s: CipherSuite   : %s", prefix, session.getCipherSuite))
      logger.trace(String.format("%s: LocalPrincipal: %s", prefix, session.getLocalPrincipal.getName))
      logger.trace(String.format("%s: PeerHost      : %s", prefix, session.getPeerHost))
      logger.trace(s"$prefix: PeerPort      : ${session.getPeerPort}")
      logger.trace(String.format("%s: PeerPrincipal : %s", prefix, session.getPeerPrincipal.getName))
      for(cs <- session.getPeerCertificates) {
        dumpCertificate(logger, prefix, cs)
      }
    }
  } catch {
    case ex:SSLException =>
      logger.warn(s"$prefix: cannot verify ssl certificate", ex)
  }

}