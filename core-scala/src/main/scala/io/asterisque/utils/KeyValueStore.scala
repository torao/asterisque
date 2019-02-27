package io.asterisque.utils

import java.io.File
import java.nio.charset.StandardCharsets
import java.util

import io.asterisque.carillon._
import org.rocksdb.{Options, RocksDB}
import org.slf4j.LoggerFactory

import scala.collection.mutable

trait KeyValueStore extends AutoCloseable {

  def get(key:Array[Byte]):Array[Byte]

  def get(key:String):Array[Byte] = get(KeyValueStore.key(key))

  def put(key:Array[Byte], value:Array[Byte]):Unit

  def put(key:String, value:Array[Byte]):Unit = put(KeyValueStore.key(key), value)

  def delete(key:Array[Byte]):Unit

  def delete(key:String):Unit = delete(KeyValueStore.key(key))

  def toMap:Map[Array[Byte], Array[Byte]]

  def subset(prefix:String):KeyValueStore
}

object KeyValueStore {
  private[KeyValueStore] val logger = LoggerFactory.getLogger(classOf[KeyValueStore])

  private[KeyValueStore] def key(key:String):Array[Byte] = key.getBytes(StandardCharsets.UTF_8)

  def apply(dir:File, createIfMissing:Boolean = true):KeyValueStore = {
    new Impl(dir, createIfMissing)
  }

  /**
    * Key-value store implementation to store application state or cache to local environment.
    */
  private[this] class Impl(dir:File, createIfMissing:Boolean) extends KeyValueStore {

    private[this] val kvs = locally {
      val options = new Options()
      options.setCreateIfMissing(createIfMissing)
      options.useFixedLengthPrefixExtractor(5)
      RocksDB.open(options, dir.toString)
    }

    def get(key:Array[Byte]):Array[Byte] = kvs.get(key)

    def put(key:Array[Byte], value:Array[Byte]):Unit = kvs.put(key, value)

    def delete(key:Array[Byte]):Unit = kvs.delete(key)

    override def close():Unit = kvs.close()

    def toMap:Map[Array[Byte], Array[Byte]] = toMap("")

    def toMap(prefix:String):Map[Array[Byte], Array[Byte]] = using(kvs.newIterator()) { it =>
      if(prefix.length == 0) it.seekToFirst() else it.seek(key(prefix))
      val map = mutable.HashMap[Array[Byte], Array[Byte]]()
      while(it.isValid) {
        val key = it.key()
        val value = it.value()
        map.put(key, value)
        it.next()
      }
      map.toMap
    }

    def subset(prefix:String):KeyValueStore = new Alias(this, prefix)
  }

  private[this] class Alias(root:Impl, prefix:String) extends KeyValueStore {
    private[this] val _prefix = key(prefix)

    private[this] def join(key:Array[Byte]):Array[Byte] = {
      val binary = new Array[Byte](_prefix.length + key.length)
      System.arraycopy(_prefix, 0, binary, 0, _prefix.length)
      System.arraycopy(key, 0, binary, _prefix.length, key.length)
      binary
    }

    def get(key:Array[Byte]):Array[Byte] = root.get(join(key))

    def put(key:Array[Byte], value:Array[Byte]):Unit = root.put(join(key), value)

    def delete(key:Array[Byte]):Unit = root.delete(join(key))

    override def close():Unit = root.close()

    def toMap:Map[Array[Byte], Array[Byte]] = root.toMap(prefix).collect {
      case (key, value) if util.Arrays.equals(key, 0, _prefix.length, _prefix, 0, _prefix.length) =>
        (util.Arrays.copyOfRange(key, _prefix.length, key.length), value)
    }

    def subset(prefix:String):KeyValueStore = new Alias(root, this.prefix + prefix)
  }

}
