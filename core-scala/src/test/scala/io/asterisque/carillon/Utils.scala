package io.asterisque.carillon

import java.io._
import java.security.{KeyPair, KeyPairGenerator}

import io.asterisque.auth.Algorithms
import io.asterisque.test
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

  @deprecated(message = "use io.asterisque.test.fs instead")
  val fs:test.fs.type = io.asterisque.test.fs

}
