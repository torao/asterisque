package io.asterisque.wire

import java.nio.ByteOrder
import java.nio.charset.{Charset, StandardCharsets}

import io.asterisque.utils.Version

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
  val Version:Version = new Version(getClass.getPackage.getImplementationVersion)

  val Std:Spec = V1

  case object V1 extends Spec(
    version = 0x0100,
    charset = StandardCharsets.UTF_8,
    endian = ByteOrder.LITTLE_ENDIAN,
    maxServiceId = 0xFF
  ) {}

}