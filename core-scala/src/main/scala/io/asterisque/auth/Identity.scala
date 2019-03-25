package io.asterisque.auth

import java.security.MessageDigest

import io.asterisque.blockchain.util.encode.Base58

sealed abstract class Identity(identity:Array[Byte]) {

  def schema:Identity.Schema

  override def toString:String = {
    val md = MessageDigest.getInstance("MD5")
    md.update((schema.prefix & 0xFF).toByte)
    val checksum = md.digest(identity)
    val buffer = new Array[Byte](identity.length + 2)
    System.arraycopy(identity, 0, buffer, 0, identity.length)
    System.arraycopy(checksum, identity.length, checksum, 0, 2)
    val base58 = Base58.encode(buffer)
    schema.prefix + Base58.ZERO * (schema.base58Size - base58.length) + base58
  }

}

object Identity {

  sealed trait Schema {
    val prefix:Char
    val byteSize:Int
    val base58Size:Int = math.floor(math.log(256) / math.log(58) * byteSize).toInt
  }

}