package io.asterisque.tools.a9

import java.io.{File, FileOutputStream}
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util

import io.asterisque.node.Context
import io.asterisque.security.TrustContext
import io.asterisque.tools._
import io.asterisque.utils.IO
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

    def approve(caDir:File, csr:File, cert:File, subject:Option[String] = None, days:Option[Int] = None, force:Boolean = false):Unit = {
      if(!force && cert.exists()) {
        exit(s"Certificate $cert already exists.")
      }
      val ca = PKI.CA(caDir)
      ca.issueCertificate(csr, cert, subject, days)
    }
  }

  object keyStore {
    def put(keyStore:File, privateKey:File, certificate:File, caCert:File, alias:String, passphrase:String, force:Boolean = false):Unit = {
      PKI.newPKCS12(keyStore, privateKey, certificate, caCert, alias, passphrase)
    }
  }

  def newKey(privateKey:File, force:Boolean = false):Unit = {
    if(!force && privateKey.exists()) {
      exit(s"Private key $privateKey already exists.")
    }
    privateKey.getAbsoluteFile.getParentFile.mkdirs()
    IO.using(new FileOutputStream(privateKey))(_ => ())
    IO.setPermission(privateKey, "rw-------")
    PKI.openssl(sh"""ecparam -genkey -name ${PKI.DEFAULT_EC_CURVE} -out $privateKey""")
    if(!IO.setPermission(privateKey, "r--------")) {
      privateKey.setWritable(false)
      privateKey.setReadable(true, true)
    }
  }

  def newCSR(privateKey:File, csr:File, subject:String, days:Int, force:Boolean = false):Unit = {
    if(!force && csr.exists()) {
      exit(s"CSR $csr already exists.")
    }
    PKI.openssl(sh"""req -new -key $privateKey -sha256 -subj $subject -days $days -batch -out $csr""")
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
