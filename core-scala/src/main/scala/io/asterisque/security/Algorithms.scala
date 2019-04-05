package io.asterisque.security

import java.io.{ByteArrayInputStream, File, FileInputStream, FileOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.security._
import java.security.cert._
import java.security.spec.PKCS8EncodedKeySpec
import java.util
import java.util.Base64

import io.asterisque.tools.PKI
import io.asterisque.utils.using
import javax.naming.ldap.LdapName
import javax.security.auth.x500.X500Principal
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

object Algorithms {
  private[this] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))

  val X509Factory:CertificateFactory = CertificateFactory.getInstance("X.509")

  object KeyStore {
    private[this] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))

    /** KeyStore としてサポートしているファイル形式 */
    val Types:Seq[String] = Seq("PKCS12", "JKS")
    logger.debug(s"supported key-store format: ${Types.mkString("[", ", ", "]")}")

    /**
      * 指定されたファイルから KeyStore をロードします。ファイルが存在しない場合やサポートしている形式ではない場合、パス
      * フレーズが間違っている場合は None を返します。
      *
      * @param file       KeyStore ファイル
      * @param passphrase KeyStore のパスフレーズ
      * @return ロードした KeyStore
      */
    def load(file:File, passphrase:Array[Char]):Option[KeyStore] = {
      Types.foreach { ksType =>
        val keyStore = java.security.KeyStore.getInstance(ksType)
        using(new FileInputStream(file)) { in =>
          try {
            keyStore.load(in, passphrase)
            logger.info(s"the key-store has been loaded as $ksType format: $file")
            return Some(keyStore)
          } catch {
            case ex:Exception =>
              logger.trace(s"cannot load key-store as type $ksType: $file; $ex")
          }
        }
      }
      None
    }
  }

  object Principal {
    def parseDName(principal:X500Principal):Seq[(String, String)] = {
      val ldapDN = new LdapName(principal.getName)
      ldapDN.getRdns.asScala.map(rdn => (rdn.getType.toUpperCase, String.valueOf(rdn.getValue)))
    }
  }

  object Cert {
    def load(file:File):Option[X509Certificate] = load(Files.readAllBytes(file.toPath))

    /**
      * @throws IllegalArgumentException 証明書パスが null か長さ 0 の場合
      * @throws CertificateException     証明書パスの検証に失敗した場合。
      */
    @throws[IllegalArgumentException]
    @throws[CertificateException]
    def load(content:Array[Byte]):Option[X509Certificate] = loads(content) match {
      case Seq() => None
      case Seq(cert) =>
        // 単一の CERTIFICATE エントリのみの場合は単独の証明書とみなす
        Some(cert)
      case certs =>
        // 複数の CERTIFICATE エントリが含まれている場合は証明書パスとして検証する
        Try(Path.verify(certs)).map(_ => certs.head).toOption
    }

    /**
      * 指定されたバイナリに含まれる複数の証明書を取り出します。バイナリは PEM 形式の PKCS#7 または複数の CERTIFICATE エントリ
      * を含むことができます。認識できる証明書が含まれていないか、PEM 形式でない場合は空のリストを返します。
      *
      * 返値の証明書は証明書パスとして検証が行われていないことに注意して下さい。証明書パスとして扱うには [[Path.load()]] を
      * 使用するか、[[Path.verify()]] で検証する必要があります。
      *
      * @param content 複数の証明書を取り出すバイナリ
      * @return バイナリに含まれる証明書
      */
    private def loads(content:Array[Byte]):Seq[X509Certificate] = {
      val entries = PEM.parse(content)
      if(entries.exists(_.name == "PKCS7")) {
        PKCS7.toCertPath(content).getCertificates.asScala.map(_.asInstanceOf[X509Certificate]).toVector
      } else if(entries.exists(_.name == "CERTIFICATE")) {
        CERTIFICATE.toX509Certificates(content)
      } else {
        Seq.empty
      }
    }

    def store(cert:X509Certificate, file:File):Unit = {
      import java.nio.file.StandardOpenOption._
      Files.write(file.toPath, cert.getEncoded, WRITE, TRUNCATE_EXISTING, CREATE)
    }

    object Path {
      private[this] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))

      /** Validation algorithm for certificate chain by [[validate()]]. */
      val VALIDATE = "PKIX"

      /**
        * 指定された証明書が証明書パスとなることを検証し [[CertPath]] を構築します。
        *
        * @param certs 証明書パスを生成する証明書
        * @return 証明書パス
        * @throws IllegalArgumentException 証明書パスが null か長さ 0 の場合
        * @throws CertificateException     証明書パスの検証に失敗した場合。
        */
      @throws[IllegalArgumentException]
      @throws[CertificateException]
      def generate(certs:Seq[X509Certificate]):CertPath = {
        verify(certs)
        X509Factory.generateCertPath(certs.asJava)
      }

      /**
        * 指定された PKCS#7 または複数の CERTIFICATE エントリから成る PEM 形式のファイルから証明書パスを復元します。
        *
        * @param file PEM formatted PKCS#7 or that contains multiple CERTIFICATE entries
        * @return certificate chain
        */
      def load(file:File):Option[CertPath] = load(Files.readAllBytes(file.toPath), file.toString)

      /**
        * 指定された PKCS#7 または複数の CERTIFICATE エントリから成る PEM 形式のバイナリから証明書パスを復元します。
        *
        * @param content PEM formatted PKCS#7 or CERTIFICATEs
        * @param src     debug information to output log
        * @return certificate chain
        */
      def load(content:Array[Byte], src:String = ""):Option[CertPath] = {
        val entries = PEM.parse(content)
        (if(entries.exists(_.name == "PKCS7")) {
          Some(PKCS7.toCertPath(content))
        } else if(entries.exists(_.name == "CERTIFICATE")) {
          val certs = CERTIFICATE.toX509Certificates(content)
          Some(generate(certs))
        } else {
          logger.debug(s"cannot be recognized as a certificate path: $src")
          None
        }).flatMap { certPath =>
          Try(verify(certPath)) match {
            case Success(_) => Some(certPath)
            case Failure(ex) =>
              logger.debug(s"invalid certificate path: $ex; $src")
              None
          }
        }
      }

      @throws[CertificateException]
      def verify(certPath:CertPath):Unit = {
        verify(certPath.getCertificates.asScala.map(_.asInstanceOf[X509Certificate]).toVector)
      }

      /**
        * 指定された証明書パスが有効かを検証します。全ての証明書が有効期限内であり、一つ後ろの証明書によって署名されている、
        * あるいは、末尾の場合は自己署名証明書であるかを検証します。
        *
        * @param certs 検証する証明書パス
        * @throws IllegalArgumentException 証明書パスが null か長さ 0 の場合
        * @throws CertificateException     証明書パスの検証に失敗した場合。
        */
      @throws[IllegalArgumentException]
      @throws[CertificateException]
      def verify(certs:Seq[X509Certificate]):Unit = {
        if(certs == null || certs.isEmpty) {
          throw new IllegalArgumentException("certificate path is not specified or is empty")
        }
        for(i <- certs.indices) {

          // all certificates must be valid
          certs(i).checkValidity()

          // all certificates must be signed by the following certificate
          val issuer = certs(i + (if(i + 1 < certs.length) 1 else 0))
          try {
            certs(i).verify(issuer.getPublicKey)
          } catch {
            case ex:SignatureException =>
              val subject = certs(i).getSubjectDN.getName
              val issue = issuer.getSubjectDN.getName
              val msg = s"certificate of $subject is not issued by $issue"
              throw new CertificateException(msg, ex)
          }
        }
      }

      /**
        * Validate the specified certificate chain. Returns true if all certificates contained in the chain are
        * valid and are certificates issued by the previous CA certificate. Note that certificate chains of length
        * 0 or 1 are also considered valid.
        *
        * @param certPath certificate chain to validate
        * @return the result of validation
        */
      def validate(trustAnchors:Set[TrustAnchor], certPath:CertPath):Try[X509Certificate] = {
        val certs = certPath.getCertificates.asScala.map(_.asInstanceOf[X509Certificate])
        val params = new PKIXParameters(trustAnchors.asJava)
        val validator = CertPathValidator.getInstance(VALIDATE)
        val revocationChecker = validator.getRevocationChecker.asInstanceOf[PKIXRevocationChecker]
        revocationChecker.setOptions(util.EnumSet.of(PKIXRevocationChecker.Option.ONLY_END_ENTITY))
        params.addCertPathChecker(revocationChecker)
        Try(validator.validate(certPath, params).asInstanceOf[PKIXCertPathValidatorResult]).map { result =>
          result.getTrustAnchor.getTrustedCert
        }.flatMap { trustedCert =>
          certs.map(cert => Try(cert.checkValidity())).collectFirst { case f:Failure[_] => f } match {
            case Some(Failure(err)) => Failure(err)
            case None => Success(trustedCert)
          }
        }.flatMap { trustedCert =>
          certs.zip(certs.drop(1)).map { case (subject, issuer) =>
            Try(subject.verify(issuer.getPublicKey))
          }.collectFirst { case f:Failure[_] => f } match {
            case Some(Failure(err)) => Failure(err)
            case None => Success(trustedCert)
          }
        }
      }
    }

  }

  object CRL {
    private[this] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))

    /**
      * Restore CRLs from specified encoded binary that contains `PKCS7` or `X509 CRL` entries as PEM format.
      */
    def loads(encoded:Array[Byte]):Seq[X509CRL] = {
      val entries = PEM.parse(encoded)
      if(entries.isEmpty) {
        logger.debug(s"no PEM entries detected")
        Seq.empty
      } else if(entries.exists(_.name == "PKCS7")) {
        val in = new ByteArrayInputStream(PKI.pkcs7ToPEM(encoded))
        Seq(X509Factory.generateCRL(in).asInstanceOf[X509CRL])
      } else {
        entries.filter(_.name.matches("X509\\s+CRL")).map { case PEM.Entry(_, _, pemEncoded) =>
          val in = new ByteArrayInputStream(pemEncoded)
          X509Factory.generateCRL(in).asInstanceOf[X509CRL]
        }
      }
    }

    def loads(file:File):Seq[X509CRL] = loads(Files.readAllBytes(file.toPath))
  }

  object PrivateKey {
    val ALGORITHM = "EC"

    /**
      * Restores private key from the specified binary in PEM or DER format.
      *
      * @param bytes private-key binary
      * @return private key
      */
    def load(bytes:Array[Byte]):PrivateKey = {
      def deserializeDER(bytes:Array[Byte]):PrivateKey = {
        val spec = new PKCS8EncodedKeySpec(bytes, ALGORITHM)
        val factory = KeyFactory.getInstance(ALGORITHM)
        factory.generatePrivate(spec)
      }

      // supports DER or PEM contained DER binary
      deserializeDER(
        PEM.parse(bytes).find(e => e.name == "PRIVATE KEY" || e.name == "EC PRIVATE KEY") match {
          case Some(entry) => entry.content
          case None => bytes
        })
    }

    def load(file:File):PrivateKey = load(Files.readAllBytes(file.toPath))

    def store(file:File, key:PrivateKey):Unit = using(new FileOutputStream(file)) { out =>
      out.write(key.getEncoded)
    }
  }

  private object PKCS7 {

    /**
      * 指定された PEM 形式の PKCS#7 バイナリから証明書パスを取り出します。
      *
      * @param content PKCS#7 バイナリ
      * @return 証明書パス
      */
    def toCertPath(content:Array[Byte]):CertPath = {
      val in = new ByteArrayInputStream(content)
      X509Factory.generateCertPath(in, "PKCS7")
    }

  }

  private object CERTIFICATE {

    /**
      * 指定された PEM 形式のバイナリから CERTIFICATE エントリを取り出します。
      *
      * @param content CERTIFICATE エントリを含むバイナリ
      * @return 証明書
      */
    def toX509Certificates(content:Array[Byte]):Seq[X509Certificate] = {
      val in = new ByteArrayInputStream(content)
      X509Factory.generateCertificates(in).asScala.map(_.asInstanceOf[X509Certificate]).toVector
    }
  }

  private[security] object PEM {
    private[this] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))

    /**
      * PEM のエントリを抽出するための正規表現。
      */
    private[this] val ENTRY = "(?m)(?s)-----BEGIN\\s+(.*?)-----\r?\n+(.*?)\r?\n-----END\\s+(.*?)-----".r

    /**
      * Class representing the entry stored in PEM.
      *
      * @param name       the name entry (e.g., "EC PRIVATE KEY", "CERTIFICATE")
      * @param attributes attributes
      * @param content    binary of entry
      */
    case class Entry(name:String, attributes:Map[String, String], content:Array[Byte])

    /**
      * Parse PEM container format and obtain internal entries. If the binary isn't PEM format like DER,
      * this will return a zero length `Seq`.
      *
      * @param bytes PEM format binary
      * @return entries that the PEM contains
      */
    def parse(bytes:Array[Byte]):Seq[Entry] = ENTRY.findAllMatchIn(new String(bytes, UTF_8)).flatMap { m =>
      val begin = m.group(1).trim().toUpperCase
      val end = m.group(3).trim().toUpperCase
      val body = m.group(2)
      if(begin == end) {
        // TODO should extract attributes from body
        Some(Entry(begin, Map.empty, Base64.getMimeDecoder.decode(body)))
      } else {
        logger.warn(s"PEM entry separators don't match: '$begin' != '$end'")
        None
      }
    }.toSeq
  }

}
