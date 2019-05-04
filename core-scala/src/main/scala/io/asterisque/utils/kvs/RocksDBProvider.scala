package io.asterisque.utils.kvs

import java.io.File
import java.net.URI

import io.asterisque.utils.{KeyValueStore, using}
import org.rocksdb.{Options, RocksDB}

/**
  * Rocks DB based key-value store implementation to store application state or cache to local environment.
  */
class RocksDBProvider extends KeyValueStoreProvider {
  override def getBuilder:PartialFunction[(String, URI), KeyValueStore] = {
    case (scheme, uri) if scheme == "rocksdb" =>
      val query = Option(uri.getQuery).map {
        _.split("&").map(_.split("=", 2) match {
          case Array(key) => (key, "")
          case arr => (arr(0), arr(1))
        }).toMap
      }.getOrElse(Map.empty)
      val createIfMissing = query.get("createIfMissing").forall(_.toBoolean)

      val dir = new File(new URI(s"file:${uri.getRawSchemeSpecificPart.takeWhile(_ != '?')}"))
      new RocksDBProvider.Storage(dir, createIfMissing)
  }
}

private object RocksDBProvider {

  private[RocksDBProvider] class Storage(dir:File, createIfMissing:Boolean) extends KeyValueStore {

    private[this] val kvs = locally {
      dir.getParentFile.mkdirs()
      val options = new Options()
      options.setCreateIfMissing(createIfMissing)
      options.useFixedLengthPrefixExtractor(5)
      RocksDB.open(options, dir.toString)
    }

    def get(key:Array[Byte]):Array[Byte] = kvs.get(key)

    def put(key:Array[Byte], value:Array[Byte]):Unit = kvs.put(key, value)

    def delete(key:Array[Byte]):Unit = kvs.delete(key)

    def foreach(f:(Array[Byte], Array[Byte]) => Unit):Unit = using(kvs.newIterator()) { it =>
      it.seekToFirst()
      while(it.isValid) {
        val key = it.key()
        val value = it.value()
        f(key, value)
        it.next()
      }
    }

    def close():Unit = kvs.close()
  }

}