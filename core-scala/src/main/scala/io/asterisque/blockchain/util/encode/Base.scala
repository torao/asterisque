package io.asterisque.blockchain.util.encode

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util

/**
  * This class converts arbitrary radix sequence to base sequences of different radix and generates character
  * representation like Base654. The arbitrary radix sequences are treated as byte arrays. Therefore, each
  * radix must be smaller than 256.
  *
  * The instance of this class is thread-safe.
  */
class Base(val convertRadix:Int, val digits:String) {
  private[this] val chars = digits.toCharArray

  private[this] val charIndex = chars.zipWithIndex.toMap

  if(chars.length != convertRadix) {
    throw new IllegalArgumentException(s"digits size ${digits.length} must be same as convert radix $convertRadix")
  }
  if(charIndex.size != convertRadix) {
    throw new IllegalArgumentException(s"duplicate digits characters detected: $digits")
  }

  /**
    * Encode the binary into string in BaseX.
    *
    * @param binary binary to encode to string
    * @return BaseX encoded string
    */
  def encode(binary:Array[Byte]):String = {
    Base.Radix.convertDigits(binary, 256, convertRadix).map { digit => chars(digit & 0xFF) }.mkString
  }

  /**
    * Decode the text into binary in BaseX.
    *
    * @param text text to decode to binary
    * @return BaseX decoded string
    * @throws IllegalArgumentException if text contains unexpected character
    */
  def decode(text:String):Array[Byte] = {
    val encoded = text.map { ch =>
      charIndex.getOrElse(ch, throw new IllegalArgumentException(s"unexpected character: '$ch'")).toByte
    }.toArray
    Base.Radix.convertDigits(encoded, convertRadix, 256)
  }

}


/**
  * This class implements functions that performs Base58 conversion between binary and character string.
  */
object Base {

  /**
    * Encode the binary into string in Base58. This implementation is compatible with Bitcoin.
    */
  object R58 extends Base(58, "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz") {

    override def encode(binary:Array[Byte]):String = {
      if(binary.length == 1 && binary(0) == 0) "1" else super.encode(binary.reverse)
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
      address(0) = (version & 0xFF).toByte
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
        throw new FormatException(s"too short: $text")
      }
      val checksum = decoded.takeRight(4)
      if(!util.Arrays.equals(checksum, sha256x2(decoded, 0, decoded.length - 4))) {
        throw new FormatException(s"invalid checksum: $text")
      }
      val version = decoded(0)
      val binary = util.Arrays.copyOfRange(decoded, 1, decoded.length - 4)
      (version, binary.reverse)
    }

    private[this] def sha256x2(binary:Array[Byte], offset:Int, length:Int):Array[Byte] = {
      val md = MessageDigest.getInstance("SHA256")
      md.update(binary, offset, length)
      val digest = md.digest()
      md.reset()
      md.digest(digest)
    }
  }


  object Radix {

    /**
      * Perform base conversion of the specified UINT8 array. To store digit in UINT8, the radix is limited to the
      * range from 2 to 256. Arrays are considered to be small digits with small index (Little Endian). e.g.,
      * if you want to convert 1234 as decimal to octal, the `from` should be Array[Byte](4, 3, 2, 1) and
      * `put` will be called with 2, 2, 3, 2.
      *
      * @param digits  digits from
      * @param base    the radix of `base`
      * @param toRadix the radix of `to`
      * @param put     toRadix digits as little endian
      * @throws IllegalArgumentException either base or toRadix is out of range
      */
    def convertDigits(digits:Array[Byte], base:Int, toRadix:Int, put:Int => Unit):Unit = {
      if(base < 2 || base > 256) {
        throw new IllegalArgumentException(s"base radix $base must be between 2 and 256")
      }
      if(toRadix < 2 || toRadix > 256) {
        throw new IllegalArgumentException(s"convert radix $toRadix must be between 2 and 256")
      }

      var value = digits.foldRight(BigInt(0)) { case (_digit, x) =>
        val digit = _digit & 0xFF
        if(digit >= base) {
          throw new IllegalArgumentException(s"digit $digit exceeds the radix $base")
        }
        x * base + digit
      }

      while(value != 0) {
        put((value % toRadix).toInt)
        value /= toRadix
      }
    }

    /**
      * Perform base conversion of the specified UINT8 array and returns new radix array.
      *
      * @param digits  digits from
      * @param base    the radix of `from`
      * @param toRadix the radix of `to`
      * @return radix converted digits
      */
    def convertDigits(digits:Array[Byte], base:Int, toRadix:Int):Array[Byte] = {
      val out = new ByteArrayOutputStream(sufficientSizeToConvertRadix(digits.length, base, toRadix))
      convertDigits(digits, base, toRadix, { digit:Int => out.write(digit) })
      out.toByteArray
    }

    /**
      * Calculate the sufficient number of digits after performing radix conversion. The return value is used to
      * prepare a fixed length buffer to convert.
      *
      * NOTE that it may be 1 greater value than the minimum number of digits that can be store because of the
      * effect of logarithmic error.
      *
      * @param numOfDigits the number of digits for base
      * @param base        base radix
      * @param toRadix     the radix convert to
      * @return sufficient number of digits to store toRadix
      */
    def sufficientSizeToConvertRadix(numOfDigits:Int, base:Int, toRadix:Int):Int = {
      math.ceil(math.log(base.toDouble) * numOfDigits / math.log(toRadix.toDouble)).toInt
    }

  }

  class FormatException(msg:String) extends Exception(msg)

}
