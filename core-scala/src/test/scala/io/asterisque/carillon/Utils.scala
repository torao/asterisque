package io.asterisque.carillon

import java.io._
import java.security.{KeyPair, KeyPairGenerator}

import io.asterisque.auth.Algorithms
import org.slf4j.LoggerFactory

object Utils {

  object keyPair {
    private[this] val logger = LoggerFactory.getLogger(getClass.getName.dropRight(1))

    def create(dir:File, privateKey:String, publicKey:String):KeyPair = {
      val generator = KeyPairGenerator.getInstance(Algorithms.Key)
      val keyPair = generator.generateKeyPair()
      using(new FileOutputStream(new File(dir, privateKey))) { out =>
        out.write(keyPair.getPrivate.getEncoded)
        logger.info(s"store private key [${new File(dir, privateKey)}] ${keyPair.getPrivate.getFormat}")
      }
      using(new FileOutputStream(new File(dir, publicKey))) { out =>
        out.write(keyPair.getPublic.getEncoded)
        logger.info(s"store public key [${new File(dir, publicKey)}] ${keyPair.getPublic.getFormat}")
      }
      keyPair
    }

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
      dir.delete()
    }

    def temp[T](owner:Object, eraseOnExit:Boolean = true)(f:File => T):T = {
      val dir = createTempDirectory(owner)
      val result = f(dir)
      if(eraseOnExit) removeDirectory(dir)
      result
    }
  }

}
