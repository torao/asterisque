package io.asterisque.wire

import java.nio.ByteOrder
import java.nio.charset.{Charset, StandardCharsets}

import io.asterisque.utils.Version
import org.slf4j.LoggerFactory

/**
  * asterisque プロトコルの仕様となる定数や変換処理などを定義します。
  */
sealed abstract class Spec(
                            val version:Short,
                            val charset:Charset,
                            val endian:ByteOrder,
                            val maxServiceId:Int
                          ) {
}

object Spec {
  private[this] val logger = LoggerFactory.getLogger(classOf[Spec])

  val VERSION:Version = {
    val num:String = Option(getClass.getPackage.getImplementationVersion)
      .getOrElse {
        logger.warn("version is not specified, assume 0.0.0")
        "0.0.0"
      }
    Version(num)
  }

  val Std:Spec = V1

  case object V1 extends Spec(
    version = 0x0100,
    charset = StandardCharsets.UTF_8,
    endian = ByteOrder.LITTLE_ENDIAN,
    maxServiceId = 0xFF
  ) {}

}