package io.asterisque.utils

import java.io.File
import java.nio.charset.StandardCharsets._
import java.nio.file.Files
import java.nio.file.StandardOpenOption._

import io.asterisque.test._
import org.specs2.Specification

class ConfigSpec extends Specification {

  def is =
    s2"""
It can load multiple files and overwrite them with later keys. $loadFromMultipleFiles
It can refere typed values. $convertTypes
"""

  private[this] def loadFromMultipleFiles = fs.temp(this) { dir =>

    // 基本となる設定
    val config1 = new File(dir, "config1.conf")
    Files.writeString(config1.toPath,
      """key = config1
        |japanese = 日本語
        |sub.key1 = subvalue1
        |sub.key2 = subvalue2
      """.stripMargin, UTF_8, CREATE_NEW, WRITE)

    // 上書きする設定
    val config2 = new File(dir, "config2.conf")
    Files.writeString(config2.toPath,"""key = config2""".stripMargin, UTF_8, CREATE_NEW, WRITE)

    val config = Config(config1, config2)
    val sub = config.getConfig("sub")

    (config("key") === "config2") and
      (config("japanese") === "日本語") and
      (sub("key1") === "subvalue1") and
      (sub("key2") === "subvalue2")
  }

  private[this] def convertTypes = fs.temp(this) { dir =>
    val file = new File(dir, "config.conf")
    Files.writeString(file.toPath,
      """string = ABC
        |int = 1024
        |long = 57293475093
        |boolean = true
        |list = [0, 1, 2]
        |map = {"A": 0, "B": 1, "C": 2}
      """.stripMargin, UTF_8, CREATE_NEW, WRITE)

    val config = Config(file)
    (config("string") === "ABC") and
      (config("unset") must throwA[NoSuchElementException]) and
      (config.get("string") === Some("ABC")) and
      (config.get("unset") === None) and
      (config.getInt("int") === Some(1024)) and
      (config.getInt("long") === None) and
      (config.getInt("string") === None) and
      (config.getInt("unset") === None) and
      (config.getOrElse("string", "") === "ABC") and
      (config.getOrElse("int", "") === "1024") and
      (config.getOrElse("long", "") === "57293475093") and
      (config.getOrElse("boolean", "") === "true") and
      (config.getOrElse("unset", "") === "") and
      (config.getOrElseInt("int", -1) === 1024) and
      (config.getOrElseInt("long", -10) === -10) and
      (config.getOrElseInt("string", -1) === -1) and
      (config.getOrElseInt("unset", -1) === -1) and
      (config.getOrElseLong("int", -10) === 1024L) and
      (config.getOrElseLong("long", -10) === 57293475093L) and
      (config.getOrElseLong("string", -10) === -10L) and
      (config.getOrElseLong("unset", -10) === -10L) and
      (config.getOrElseBoolean("string", default = false) === false) and
      (config.getOrElseBoolean("boolean", default = false) === true) and
      (config.getOrElseBoolean("int", default = false) === false) and
      (config.getOrElseBoolean("unset", default = false) === false) and
      (config.getList("list").map(_.asInstanceOf[Int]).toList === List(0, 1, 2)) and
      (config.getList("string").toList.length === 0) and
      (config.getList("unset").toList.length === 0) and
      (config.getMap("map") === Map("A" -> 0, "B" -> 1, "C" -> 2)) and
      (config.getMap("string").size === 0) and
      (config.getMap("unset").size === 0) and
      success
  }
}
