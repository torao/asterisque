package io.asterisque.blockchain.util.encode

import java.security.MessageDigest
import java.util

/**
  * This class implements functions that encodes/decodes Bitcoin's Base58 between binary and character string.
  */
object Base58 {
  private[this] val RADIX = 58
  private[this] val DIGITS = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toArray

  val ZERO = DIGITS(0)

  private[this] val DIGIT_TO_BYTE = {
    val arr = new Array[Int](DIGITS.max + 1)
    util.Arrays.fill(arr, -1)
    DIGITS.zipWithIndex.foreach { case (digit, i) =>
      arr(digit) = i
    }
    arr
  }

  private[this] val ESTIMATE_FACTOR = math.log(256) / math.log(RADIX)

  assert(DIGITS.length == RADIX)

  /**
    * Encode the specified byte sequence to Bitcoin-like Base58, assuming it is a 256-base digits sorted in BigEndian.
    *
    * @param binary binary to encode to string
    * @return Base86 encoded string
    */
  def encode(binary:Array[Byte]):String = if(binary.length == 0 || (binary.length == 1 && binary(0) == 0)) "" else {
    var value = BigInt(1, binary)
    val buffer = new StringBuilder(sufficientSizeToConvertRadix(binary.length))
    while(value != 0) {
      buffer.append(DIGITS((value % RADIX).toInt))
      value /= RADIX
    }
    buffer.reverse.toString()
  }

  /**
    * Decode the specified string as Bitcoin-like Base58 to binary, assuming 256-base digits sorted in BigEndian.
    *
    * @param text text to decode to binary
    * @return BaseX decoded string
    * @throws IllegalArgumentException if text contains unexpected character
    */
  def decode(text:String):Array[Byte] = {
    val value = text.map { ch =>
      val byte = if(ch >= DIGIT_TO_BYTE.length) -1 else DIGIT_TO_BYTE(ch)
      if(byte < 0) {
        throw new IllegalArgumentException(s"unexpected character: '$ch'")
      } else {
        byte
      }
    }.foldLeft(BigInt(0)) { case (sum, digit) => (sum * RADIX) + (digit & 0xFF) }
    if(value == 0) Array.empty else value.toByteArray.dropWhile(_ == 0)
  }

  /**
    * Encode the binary into string with version and checksum.
    *
    * @param version version of this binary
    * @param binary  binary to encode to string
    * @return Base58 encoded string that contains version and checksum
    */
  def encodeWithCheck(version:Byte, binary:Array[Byte]):String = {
    val address = new Array[Byte](1 + binary.length + 4)
    address(0) = version
    System.arraycopy(binary, 0, address, 1, binary.length)
    val checksum = sha256x2(address, 0, 1 + binary.length)
    System.arraycopy(checksum, 0, address, 1 + binary.length, 4)
    encode(address)
  }

  /**
    * Decode the Base58 text into binary with version and checksum.
    *
    * @param text string to decode
    * @return (version, decoded_binary)
    */
  def decodeWithCheck(text:String):(Byte, Array[Byte]) = {
    val decoded = decode(text)
    if(decoded.length < 5) {
      throw new FormatException(s"encoded text too short: $text")
    }
    val checksum = sha256x2(decoded, 0, decoded.length - 4).take(4)
    if(!util.Arrays.equals(decoded, decoded.length - 4, decoded.length, checksum, 0, 4)) {
      val version = decoded(0)
      val binary = decoded.drop(1).dropRight(4).map(_ & 0xFF).mkString("[", ",", "]")
      val cs1 = decoded.takeRight(4).map(_ & 0xFF).mkString("[", ",", "]")
      val cs2 = checksum.map(_ & 0xFF).mkString("[", ",", "]")
      throw new FormatException(s"invalid checksum: $text = ($version, $binary), $cs1 != $cs2")
    }
    val version = decoded(0)
    val binary = util.Arrays.copyOfRange(decoded, 1, decoded.length - 4)
    (version, binary)
  }

  /**
    * Calculate the sufficient number of digits after performing radix conversion. The return value is used to
    * prepare a fixed length buffer to convert. Note that it may be 1 greater value than the minimum number of
    * digits that can be store because of the effect of logarithmic error.
    *
    * @param numOfDigits the number of digits for base
    * @return sufficient number of digits to store toRadix
    */
  private[this] def sufficientSizeToConvertRadix(numOfDigits:Int):Int = math.ceil(ESTIMATE_FACTOR * numOfDigits).toInt

  private[this] def sha256x2(binary:Array[Byte], offset:Int, length:Int):Array[Byte] = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(binary, offset, length)
    val digest = md.digest()
    md.reset()
    md.digest(digest)
  }

  class FormatException(message: String) extends Exception(message)

}
