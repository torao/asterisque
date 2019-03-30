package io.asterisque.utils

import java.io.File
import java.nio.charset.StandardCharsets._
import java.nio.file.Files
import java.nio.file.StandardOpenOption._

import io.asterisque.test._
import org.slf4j.LoggerFactory
import org.specs2.Specification

class CacheSpec extends Specification {
  def is =
    s2"""
It should cache object for file. $getForFileCache
It should cache object for files in a directory. $getForDirCache
"""

  private[this] val logger = LoggerFactory.getLogger(getClass)

  private[this] def getForFileCache = fs.temp(this) { dir =>
    object TextTransformer extends Cache.FileTransformer[String] {
      override def defaultValue(file:File):String = "default value"

      override def transform(file:File):String = Files.readString(file.toPath, UTF_8)
    }

    val cache = new Cache[String](TextTransformer, 0L)
    val file = new File(dir, "a.txt")

    // 存在しないファイル
    val fileNotExists = cache.get(file) === TextTransformer.defaultValue(file)

    // 存在するファイル
    val text1 = randomASCII(5329, 1024)
    Files.writeString(file.toPath, text1, UTF_8, WRITE, CREATE_NEW)
    val fileExists = (text1.length === 1024) and (cache.get(file) === text1)

    // 書き換えられたファイル
    val text2 = randomASCII(278498, 256)
    Files.writeString(file.toPath, text2, UTF_8, WRITE, TRUNCATE_EXISTING)
    Thread.sleep(1)
    val fileUpdated = (text2.length === 256) and (cache.get(file) === text2)

    // タイムスタンプが変わっていなくてもサイズが変われば再読込される
    val lastModified = file.lastModified()
    val text3 = randomASCII(492840, 10)
    Files.writeString(file.toPath, text3, UTF_8, WRITE, TRUNCATE_EXISTING)
    file.setLastModified(lastModified)
    Thread.sleep(1)
    val fileIsNotReloaded = (text3.length === 10) and (cache.get(file) === text3)

    fileNotExists and fileExists and fileUpdated and fileIsNotReloaded
  }

  private[this] def getForDirCache = fs.temp(this) { dir =>
    object DirTransformer extends Cache.DirTransformer[List[Int]] {
      override def transform(files:Seq[File]):List[Int] = files.map { file =>
        Files.readString(file.toPath, UTF_8).toInt
      }.toList

      override def defaultValue(dir:File):List[Int] = List.empty
    }

    val cache = new Cache[List[Int]](DirTransformer, 0L)

    // 存在しないディレクトリ
    val subdir = new File(dir, "notexists")
    val dirNotExists = cache.get(subdir) === DirTransformer.defaultValue(subdir)

    // ディレクトリ直下のファイルを参照する
    (0 to 8).foreach { i =>
      val file = new File(dir, f"$i%d.txt")
      Files.writeString(file.toPath, s"$i", UTF_8, WRITE, CREATE_NEW)
      file
    }
    val filesExists = cache.get(dir).sorted === (0 to 8).toList

    // ファイルの生成を検知する
    Files.writeString(new File(dir, "9.txt").toPath, "9", UTF_8, WRITE, CREATE_NEW)
    Thread.sleep(1)
    val fileCreate = cache.get(dir).sorted === (0 to 9).toList

    // ファイルの更新を検知する
    Files.writeString(new File(dir, "0.txt").toPath, "100", UTF_8, WRITE, TRUNCATE_EXISTING)
    Thread.sleep(1)
    val fileUpdate = cache.get(dir).max === 100

    // ファイルの削除を検知する
    new File(dir, "0.txt").delete()
    Thread.sleep(1)
    val fileDelete = cache.get(dir).min === 1

    dirNotExists and filesExists and fileCreate and fileUpdate and fileDelete
  }

}
