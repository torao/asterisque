package io.asterisque.tools.a9

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util

import io.asterisque.node.Context
import io.asterisque.security.{Algorithms, TrustContext}
import io.asterisque.tools._
import org.slf4j.LoggerFactory

object Commands {
  private[this] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))

  val CSR_FILE = "private-csr.pem"
  val CERT_FILE = "private-cert.pem"

  /**
    * ローカル CA 管理用のコマンド。
    */
  object ca {

    def init(dir:File, subject:String, days:Int, force:Boolean = false):Unit = {
      if(!force && !canInstallTo(dir)) {
        exit(s"Non-empty directory $dir already exists.")
      }
      PKI.CA.newRootCA(dir, subject, days = days)
    }

    def approve(caDir:File, nodeDir:File, subject:Option[String] = None, days:Option[Int] = None, force:Boolean = false):Unit = {
      logger.debug(s"ca.approve($caDir, $nodeDir, $subject, $days, $force)")
      val ca = PKI.CA(caDir)
      val cname = Algorithms.Principal.parseDName(ca.certificate.getSubjectX500Principal).toMap.getOrElse("CN", "unknown-ca")

      val security = new File(new File(nodeDir, Context.CONF_DIR), Context.TRUST_CONTEXT_DIR)
      val csr = new File(security, CSR_FILE)
      val cert = new File(security, CERT_FILE)
      val cacert = new File(new File(security, TrustContext.TRUSTED_CA_CERTS_DIR), s"$cname.pk7")
      if(!force && (cert.isFile || cacert.isFile)) {
        exit(s"Certificate $cert already exists.")
      }
      ca.issueCertificate(csr, cert, subject, days)

      // install trusted certificate path
      ca.exportCertPathWithCRLAsPKCS7(cacert)
    }
  }

  def init(dir:File, subject:String, days:Int, key:Option[File] = None, force:Boolean = false):Unit = {
    logger.info(s"initializing directory $dir")

    // create all directories
    if(!force && !canInstallTo(dir)) {
      exit(s"Non-empty directory $dir already exists.")
    }
    val conf = new File(dir, "conf")
    val security = new File(conf, Context.TRUST_CONTEXT_DIR)
    mkdirs(dir, conf, security,
      new File(security, TrustContext.TRUSTED_CA_CERTS_DIR),
      new File(security, TrustContext.BLOCKED_CERTS_DIR))

    // create private key
    val keyFile = new File(security, "private.pem")
    keyFile.createNewFile()
    if(keyFile.toPath.getFileSystem.supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(keyFile.toPath, util.EnumSet.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE
      ))
    }
    key match {
      case Some(f) =>
        Files.copy(f.toPath, keyFile.toPath)
      case None =>
        PKI.openssl(sh"""ecparam -genkey -name ${PKI.DEFAULT_EC_CURVE} -out $keyFile""")
    }
    keyFile.setReadOnly()

    // create CSR
    val csrFile = new File(security, CSR_FILE)
    PKI.openssl(sh"""req -new -key $keyFile -sha256 -subj $subject -days $days -batch -out $csrFile""")
  }

  private[this] def mkdirs(dirs:File*):Unit = dirs.foreach { dir =>
    if(!dir.isDirectory && !dir.mkdirs()) {
      exit(s"Cannot create directory $dir.")
    }
  }

  /**
    * 指定されたディレクトリに新しい構成をインストールできるか判定します。ディレクトリが存在しないか、または全てのサブディレ
    * クトリが空、もしくは内包するファイルが 0 バイトの場合に true を返します。
    *
    * @param dir 判定するディレクトリ
    * @return ディレクトリに構成をインストールできる場合 true
    */
  private[this] def canInstallTo(dir:File):Boolean = dir match {
    case d if d.isDirectory =>
      Option(d.listFiles()).forall(_.map(canInstallTo).forall(identity))
    case f if f.isFile && f.length > 0 =>
      logger.error(s"non-empty file $f is detected")
      false
    case x => println(x); !x.exists()
  }

  private[this] def exit(msg:String, errorCode:Int = 1):Nothing = {
    System.err.println(s"ERROR: $msg")
    System.exit(errorCode).asInstanceOf[Nothing]
  }

}
