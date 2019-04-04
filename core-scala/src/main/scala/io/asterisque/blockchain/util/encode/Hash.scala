package io.asterisque.blockchain.util.encode

import org.apache.commons.math3.random.Well512a

object Hash {

  def newRandom(length:Int, seed:Long):Array[Byte] = {
    val random = new Well512a(seed)
    val binary = new Array[Byte](length)
    random.nextBytes(binary)
    binary
  }

  def toString(binary:Array[Byte]):String = {
    BigInt(binary).toString(16)
  }
}
