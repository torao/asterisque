package io.asterisque.utils

import java.io.File
import java.util.Random

import io.asterisque.carillon.Utils.fs
import io.asterisque.carillon.using
import org.specs2.Specification

class KeyValueStoreSpec extends Specification {
  def is =
    s2"""
It can refer the value put and vanish on delete. $putAndGetAndDelete
       $subsetOperation
    """

  private[this] def putAndGetAndDelete = fs.temp(this) { dir =>
    val random = new Random(489720480283L)
    val keyValues = (0 until 100).map { _ =>
      val key = random.ints(1024).toArray.map(_.toByte)
      val value = random.ints(512).toArray.map(_.toByte)
      (key, value)
    }
    using(KeyValueStore(new File(dir, "cache"))) { kvs =>
      keyValues.foreach { case (key, value) =>
        kvs.put(key, value)
      }
      (kvs.toMap.size === keyValues.length) and keyValues.zipWithIndex.map { case ((key, value), i) =>
        (kvs.get(key) === value) and {
          kvs.delete(key)
          kvs.get(key) must beNull
        } and (kvs.toMap.size === keyValues.size - i - 1)
      }.reduceLeft(_ and _)
    }
  }

  private[this] def subsetOperation = fs.temp(this) { dir =>
    val random = new Random(623894759L)
    val keyValues = (0 until 100).map { _ =>
      val key = random.ints(1024).toArray.map(_.toByte)
      val value = random.ints(512).toArray.map(_.toByte)
      (key, value)
    }
    using(KeyValueStore(new File(dir, "cache"))) { root =>
      val store = (0 until 10).map{ i =>
        val kvs = root.subset(s"c$i")
        keyValues.foreach { case (key, value) => kvs.put(key, value)}
        kvs.toMap.size === keyValues.length
      }.reduceLeft(_ and _)

      val delete = (0 until 10).flatMap { i =>
        val kvs = root.subset(s"c$i")
        keyValues.zipWithIndex.map { case ((key, value), i) =>
          (kvs.get(key) === value) and {
            kvs.delete(key)
            kvs.get(key) must beNull
          } and (kvs.toMap.size === keyValues.size - i - 1)
        }
      }.reduceLeft(_ and _)

      store and delete
    }
  }

}
