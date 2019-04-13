package io.asterisque.utils

import java.io.File
import java.net.URI
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

import io.asterisque.test._
import org.specs2.Specification
import org.specs2.matcher.MatchResult

class KeyValueStoreSpec extends Specification {
  def is =
    s2"""
It can refer the value put and vanish on delete. $putAndGetAndDelete
It can do same operation for sub-kvs. $subsetOperation
It raise exception for unsupported uri. $unsupportedURI
    """

  protected def testURIs(workdir:File):Seq[URI] = Seq(
    "mem:.",
    s"rocksdb:${new File(workdir, "rocksdb").toURI.getRawSchemeSpecificPart}?createIfMissing=true"
  ).map(URI.create)

  private[this] def putAndGetAndDelete = fs.temp(this) { dir =>
    testURIs(dir).map(KeyValueStore.getInstance).map { kvs =>
      using(kvs)(test)
    }.reduceLeft(_ and _)
  }

  private[this] def test(kvs:KeyValueStore):MatchResult[_] = {
    val random = new Random(489720480283L)
    val keyValues = (0 until 100).map { _ =>
      val key = random.ints(1024).toArray.map(_.toByte)
      val value = random.ints(512).toArray.map(_.toByte)
      (key, value)
    }

    val initialState = kvs.toMap.isEmpty must beTrue

    val putAndGet = keyValues.map { case (key, value) =>
      kvs.put(key, value)
      kvs.get(key) === value
    }.reduceLeft(_ and _)

    val iteration = {
      val size = new AtomicInteger()
      val cmp = kvs.toSeq.map { case (key, value) =>
        size.incrementAndGet()
        keyValues.exists(kv => kv._1.sameElements(key) && kv._2.sameElements(value)) must beTrue
      }.reduceLeft(_ and _)
      cmp and (size.get() === keyValues.length)
    }

    val sizeCompetition = (kvs.toMap.size === keyValues.length).setMessage(kvs.toString)

    val result = keyValues.zipWithIndex.map { case ((key, _), i) => {
      kvs.delete(key)
      kvs.get(key) must beNull
    } and (kvs.toMap.size === keyValues.size - i - 1)
    }.reduceLeft(_ and _)

    initialState and putAndGet and iteration and sizeCompetition and result
  }

  private[this] def subsetOperation = fs.temp(this) { dir =>
    testURIs(dir).map(KeyValueStore.getInstance).map { root =>
      using(root) { _ =>
        (0 until 10).map { i =>
          val prefix = s"X$i"
          val kvs = root.subKVS(prefix)

          test(kvs) and {
            val value = randomByteArray(29813400, 64)
            root.put(s"${prefix}A", value)
            kvs.get("A") === value
          } and {
            val value = randomByteArray(982734, 64)
            kvs.put("B", value)
            root.get(s"${prefix}B") === value
          } and {
            val value = randomByteArray(47820954, 64)
            root.put(s"${prefix}C", value)
            kvs.delete("C")
            root.get(s"${prefix}C") must beNull
          } and {
            val value = randomByteArray(268478374, 64)
            kvs.put("D", value)
            root.delete(s"${prefix}D")
            kvs.get("D") must beNull
          }
        }.reduceLeft(_ and _)
      }
    }.reduceLeft(_ and _)
  }

  private[this] def unsupportedURI = {
    KeyValueStore.getInstance(new URI("null:/foo/bar")) must throwA[ServiceNotFoundException]
  }

}
