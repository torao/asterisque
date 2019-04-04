package io.asterisque

import java.io._
import java.security.PrivateKey
import java.security.cert.X509Certificate

import io.asterisque.auth.Certificate
import io.asterisque.carillon.using
import io.asterisque.wire.Envelope
import io.asterisque.wire.rpc.ObjectMapper
import org.slf4j.LoggerFactory

package object test {

  private[this] val ASCII_CHARS = (' ' to 0x7F).map(_.toChar).filter(c => Character.isDefined(c) && !Character.isISOControl(c) && !Character.isWhitespace(c)).toArray

  def randomString(seed:Int, length:Int):String = {
    val random = new scala.util.Random(seed)
    random.nextString(length)
  }

  def randomASCII(seed:Int, length:Int):String = {
    val random = new scala.util.Random(seed)
    (0 until length).map(_ => ASCII_CHARS(random.nextInt(ASCII_CHARS.length))).mkString
  }

  def randomByteArray(seed:Int, length:Int):Array[Byte] = {
    val random = new scala.util.Random(seed)
    val bytes = new Array[Byte](length)
    random.nextBytes(bytes)
    bytes
  }

  object fs {
    private[this] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))
    private[this] val tempRoot = new File(".temp").getCanonicalFile
    tempRoot.mkdirs()

    private[this] def nextSequence():Long = synchronized {
      tempRoot.mkdirs()
      val raf = new RandomAccessFile(new File(tempRoot, ".seq"), "rw")
      raf.getChannel.lock()
      val num = if(raf.length() == 0) 0L else raf.readLong()
      raf.seek(0)
      raf.writeLong(num + 1)
      raf.close()
      num
    }

    def copy(src:File, dst:File):Long = using(new FileInputStream(src).getChannel) { in =>
      using(new FileOutputStream(dst).getChannel) { out =>
        in.transferTo(0, Long.MaxValue, out)
      }
    }

    def cleanup():Unit = {
      removeDirectory(tempRoot)
    }

    def createTempDirectory(owner:Object):File = {
      val parent = new File(tempRoot, owner.getClass.getName)
      parent.mkdirs()
      val temp = new File(parent, f"${nextSequence()}%06d")
      temp.mkdirs()
      temp.getCanonicalFile
    }

    def removeDirectory(dir:File):Unit = {
      if(!dir.toString.startsWith(tempRoot.toString)) {
        throw new IOException(s"directory $dir may not be controlled as temporary directory")
      }
      Option(dir.listFiles()).getOrElse(Array.empty).foreach { file =>
        if(file.isFile) file.delete() else removeDirectory(file)
      }
      if(!dir.delete()) {
        logger.warn(s"directory deletion failed: $dir")
      }
    }

    def temp[T](owner:Object, eraseOnExit:Boolean = true)(f:File => T):T = {
      val dir = createTempDirectory(owner)
      val result = f(dir)
      if(eraseOnExit) removeDirectory(dir)
      result
    }
  }

  lazy val NODE_CERTS:Seq[(PrivateKey, X509Certificate)] = {
    val ca = new CertificateAuthority()
    (0 until 3).map(i => ca.newPrivateKeyAndCertificate(f"node$i%02d"))
  }

  lazy val CERT_ENVELOPES:Seq[Envelope] = {
    val (privateKey, publicKey) = NODE_CERTS.head
    NODE_CERTS.drop(1).zipWithIndex.map { case ((_, x509), i) =>
      val cert = Certificate(x509, Map("serial" -> i.toString))
      Envelope.seal(ObjectMapper.CERTIFICATE.encode(cert), publicKey, privateKey)
    }
  }

  case class KeyCertPair(key:PrivateKey, certificate:X509Certificate)
}
