package io.asterisque.security

import java.io.File
import java.security.KeyStore
import java.security.cert.{CertPath, CertificateException, X509Certificate}

import io.asterisque.security.TrustContext._
import io.asterisque.tools.PKI
import io.asterisque.utils.Cache.{DirTransformer, FileTransformer}
import io.asterisque.utils.{Cache, Debug, IO}
import javax.annotation.Nonnull
import javax.net.ssl._
import org.apache.commons.codec.binary.Hex
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable

/**
  * 証明書の信頼性を検証するためのクラスです。
  */
class TrustContext private[TrustContext](dir:File, passphrase:String) {
  // TODO 証明書の有効期限を返すメソッドを作成し、タイマーで期限切れを古いわける処理を起動する

  private[this] val keyStore = new Cache(KeyStoreTransformer)

  private[this] val keyManagers = new Cache(KeyManagersTransformer)

  private[this] val verifier = new Cache(VerifierTransformer)

  /** プライベートキーと証明書が保存されている PKCS#12 または JKS 形式のファイル */
  private val keyStoreFile:File = new File(dir, PRIVATE_KEY_FILE)

  /** 信頼済み CA 証明書パスとその CRL が PEM または PKCS#7 形式のファイルとして保存されているディレクトリ */
  private val trustedCertsDirectory:File = new File(dir, TRUSTED_CA_CERTS_DIR)

  /** ブロック済み証明書が PEM 形式のファイルとして保存されているディレクトリ */
  private val blockedCertsDirectory:File = new File(dir, BLOCKED_CERTS_DIR)

  /**
    * KeyStore に保存されているすべてのエイリアスに対する証明書パスを参照します。
    *
    * @return すべての証明書パス
    */
  def getCertPaths:Map[String, CertPath] = {
    val ks = keyStore.get(keyStoreFile)
    ks.aliases().asScala.map { alias =>
      val certs = ks.getCertificateChain(alias).toSeq.map(_.asInstanceOf[X509Certificate])
      val certPath = Algorithms.Cert.Path.generate(certs)
      (alias, certPath)
    }.toMap
  }

  def verify(certPath:CertPath):Unit = verifier.get(dir).verify(certPath)

  def verify(certPath:Seq[X509Certificate]):Unit = verifier.get(dir).verify(certPath)

  /**
    * 指定された CA 証明書パスをこの TrustContext の信用済み CA としてデプロイします。
    *
    * @param caCertPath 信用済みにする CA 証明書パス
    * @throws CertificateException 指定されたファイルを証明書として認識できない場合
    */
  @throws[CertificateException]
  def deployTrustedCA(caCertPath:File):Unit = TrustedCA(caCertPath) match {
    case Some(trustedCA) =>
      val target = trustedCA.certPath.getCertificates.asScala.head.asInstanceOf[X509Certificate]
      val base:String = Algorithms.Principal.parseDName(target.getSubjectX500Principal)
        .find(_._1 == "CN").map(_._2)
        .getOrElse(Hex.encodeHexString(target.getEncoded).take(32))
      deploy(trustedCertsDirectory, base) { file =>
        IO.copy(caCertPath, file)
      }
    case None =>
      throw new CertificateException(s"specified file is not recognized as certificate path: $caCertPath")
  }

  /**
    * 指定された証明書ファイルをこの TrustedContext のブロック済み証明書としてデプロイします。
    *
    * @param certFile ブロックする証明書
    * @throws CertificateException 指定されたファイルを証明書として認識できない場合
    */
  @throws[CertificateException]
  def deployBlocked(certFile:File):Unit = Algorithms.Cert.load(certFile) match {
    case Some(cert) => deployBlocked(cert)
    case None => throw new CertificateException(s"specified file is not recognized as certificate: $certFile")
  }

  /**
    * 指定された証明書ファイルをこの TrustedContext のブロック済み証明書としてデプロイします。
    *
    * @param cert ブロックする証明書
    * @throws CertificateException 指定されたファイルを証明書として認識できない場合
    */
  @throws[CertificateException]
  def deployBlocked(cert:X509Certificate):Unit = {
    val base:String = Algorithms.Principal.parseDName(cert.getSubjectX500Principal)
      .find(_._1 == "CN").map(_._2)
      .getOrElse(Hex.encodeHexString(cert.getEncoded).take(32))
    deploy(blockedCertsDirectory, base) { file =>
      Algorithms.Cert.store(cert, file)
    }
  }

  private[this] def deploy(dir:File, base:String)(f:File => Unit):Unit = {
    for(i <- 0 until Short.MaxValue) {
      val name = base + (if(i == 0) "" else f"_$i%04X") + ".pem"
      val file = new File(dir, name)
      if(file.createNewFile()) {
        f(file)
      }
    }
    val msg = s"the deployment destination namespace is fully used: ${new File(dir, base)}*.pem"
    throw new IllegalStateException(msg)
  }

  /**
    * この TrustContext の信頼定義から TLS 通信のための KeyManager を参照します。
    */
  @Nonnull
  def getKeyManagers:Array[KeyManager] = keyManagers.get(keyStoreFile).toArray

  /**
    * この TrustContext の信頼定義から TLS 通信のための TrustManager を参照します。
    */
  @Nonnull
  def getTrustManagers:Array[TrustManager] = Array(verifier.get(dir).TRUST_MANAGER)

  /**
    * ファイルを読み出して KeyStore を生成する Transformer です。
    */
  private[this] object KeyStoreTransformer extends FileTransformer[KeyStore] {
    override def defaultValue(file:File):KeyStore = {
      KeyStore.getInstance(KeyStore.getDefaultType)
    }

    override def transform(file:File):KeyStore = {
      Algorithms.KeyStore.load(file, passphrase.toCharArray).getOrElse(defaultValue(file))
    }
  }

  /**
    * KeyStore ファイルから KeyManager をロードする Transformer です。
    */
  private[this] object KeyManagersTransformer extends FileTransformer[Seq[KeyManager]] {
    override def defaultValue(file:File):Seq[KeyManager] = Seq.empty

    override def transform(file:File):Seq[KeyManager] = {
      val ks = keyStore.get(keyStoreFile)
      val kmf = KeyManagerFactory.getInstance("SunX509")
      kmf.init(ks, passphrase.toCharArray)
      kmf.getKeyManagers.toSeq
    }
  }

  /**
    * KeyStore ファイル、trusted または blocked ディレクトリから TrustManager をロードする Transformer です。
    */
  private[this] object VerifierTransformer extends DirTransformer[Verifier] {
    private[this] val root = dir.getCanonicalFile.toURI

    override def defaultValue(dir:File):Verifier = new Verifier(Seq.empty, Seq.empty)

    override def transform(files:Seq[File]):Verifier = {

      // キーストアに保存されている自身の証明書を発行した CA は暗黙的に信頼済み CA に追加する
      val ks = keyStore.get(keyStoreFile)
      val defaultTrustedCAs = ks.aliases().asScala
        .map(alias => ks.getCertificateChain(alias).drop(1))
        .map(certs => Algorithms.Cert.Path.generate(certs.map(_.asInstanceOf[X509Certificate])))
        .map(certPath => TrustedCA(certPath, Seq.empty))
        .toSeq

      val trustedCAs = mutable.Buffer[TrustedCA](defaultTrustedCAs:_*)
      val blocked = mutable.Buffer[X509Certificate]()
      files.filter { file =>
        if(file.length() > MAX_CERT_SIZE_TO_READ) {
          logger.debug(f"file too large: $file (${file.length}%,d bytes > $MAX_CERT_SIZE_TO_READ%,d), skip")
          false
        } else true
      }.foreach { file =>
        root.relativize(file.getCanonicalFile.toURI).toString match {
          case caCertPath if caCertPath.startsWith(s"$TRUSTED_CA_CERTS_DIR/") =>
            TrustedCA(file).foreach(ca => trustedCAs.append(ca))
          case blockedPath if blockedPath.startsWith(s"$BLOCKED_CERTS_DIR/") =>
            Algorithms.Cert.load(file).foreach(cert => blocked.append(cert))
          case _ => None
        }
      }
      locally {
        val cas = trustedCAs
          .map(_.certPath.getCertificates.asScala.head.asInstanceOf[X509Certificate].getSubjectDN.getName)
          .map(x => Debug.toString(x))
          .mkString("[", ", ", "]")
        val bks = blocked.map(_.getSubjectDN.getName).map(x => Debug.toString(x)).mkString("[", ", ", "]")
        logger.info(s"using trusted CA$cas, blocked$bks")
      }
      new Verifier(trustedCAs, blocked)
    }
  }

}

object TrustContext {
  private[TrustContext] val logger = LoggerFactory.getLogger(classOf[TrustContext])

  val PRIVATE_KEY_FILE:String = "private.p12"

  val TRUSTED_CA_CERTS_DIR:String = "trusted"

  val BLOCKED_CERTS_DIR:String = "blocked"

  val MAX_CERT_SIZE_TO_READ:Long = 512 * 1024

  /**
    * 指定されたディレクトリにマウントする TrustContext を作成します。
    *
    * @param dir        TrustContext ディレクトリ
    * @param passphrase 秘密鍵のパスフレーズ
    * @return TrustContext
    */
  def apply(dir:File, passphrase:String):TrustContext = new TrustContext(dir, passphrase)

  /**
    * 指定されたディレクトリに新規の TrustContext 用ディレクトリを作成します。
    *
    * @param dir        TrustContext ディレクトリ
    * @param passphrase 秘密鍵のパスフレーズ
    * @return
    */
  def newTrustContext(dir:File, ca:PKI.CA, passphrase:String, subject:String):TrustContext = {
    dir.mkdirs()
    val context = new TrustContext(dir, passphrase)

    // 秘密鍵と証明書の作成 (署名した CA は暗黙的に信頼済み CA と見なされる)
    ca.newPKCS12KeyStore(context.keyStoreFile, "ecdsa", passphrase, subject)
    context.keyStoreFile.setReadable(false)
    context.keyStoreFile.setReadable(true, true)
    context.keyStoreFile.setWritable(false)
    context.keyStoreFile.setWritable(true, true)

    // ディレクトリの作成
    context.trustedCertsDirectory.mkdirs()
    context.trustedCertsDirectory.setExecutable(false)
    context.trustedCertsDirectory.setExecutable(true, true)
    context.blockedCertsDirectory.mkdirs()
    context.blockedCertsDirectory.setExecutable(false)
    context.blockedCertsDirectory.setExecutable(true, true)

    context
  }

}