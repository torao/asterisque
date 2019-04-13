package io.asterisque.utils

import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.ServiceLoader

import io.asterisque.utils.KeyValueStore.SubKVS
import io.asterisque.utils.kvs.KeyValueStoreProvider
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable

trait KeyValueStore extends AutoCloseable {

  def get(key:Array[Byte]):Array[Byte]

  def get(key:String):Array[Byte] = get(KeyValueStore.key(key))

  def put(key:Array[Byte], value:Array[Byte]):Unit

  def put(key:String, value:Array[Byte]):Unit = put(KeyValueStore.key(key), value)

  def delete(key:Array[Byte]):Unit

  def delete(key:String):Unit = delete(KeyValueStore.key(key))

  def foreach(f:(Array[Byte], Array[Byte]) => Unit):Unit

  def toSeq:Seq[(Array[Byte], Array[Byte])] = {
    val seq = mutable.Buffer[(Array[Byte], Array[Byte])]()
    foreach((key, value) => seq.append((key, value)))
    seq
  }

  def toMap:Map[Array[Byte], Array[Byte]] = toSeq.toMap

  def subKVS(prefix:Array[Byte]):KeyValueStore = new SubKVS(this, prefix)

  def subKVS(prefix:String):KeyValueStore = subKVS(KeyValueStore.key(prefix))
}

object KeyValueStore {
  private[KeyValueStore] val logger = LoggerFactory.getLogger(classOf[KeyValueStore])

  private[KeyValueStore] def key(key:String):Array[Byte] = key.getBytes(StandardCharsets.UTF_8)

  /**
    * 指定された URI に対する KeyValueStore 実装を参照します。URI は `kvs:` で始まる必要があります。
    *
    * @param uri KVS の URI
    * @return KVS
    * @throws ServiceNotFoundException URI に対するプロバイダが見つからない場合
    */
  @throws[ServiceNotFoundException]
  def getInstance(uri:URI):KeyValueStore = {
    val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("")
    val loader = ServiceLoader.load(classOf[KeyValueStoreProvider])
    val builder = loader.iterator().asScala.map(_.getBuilder).reduceLeft((a, b) => a.orElse(b))
    try {
      builder.apply((scheme, uri))
    } catch {
      case _:MatchError =>
        throw new ServiceNotFoundException(s"unsupported key-value storage uri: $uri")
    }
  }

  def local(dir:File, createIfMissing:Boolean = true):KeyValueStore = {
    val part = dir.toURI.getRawSchemeSpecificPart
    getInstance(URI.create(s"rocksdb:$part?createIfMissing=$createIfMissing"))
  }

  def memory():KeyValueStore = getInstance(URI.create(s"mem:."))

  private[KeyValueStore] class SubKVS(root:KeyValueStore, prefix:Array[Byte]) extends KeyValueStore {

    private[this] def join(key:Array[Byte]):Array[Byte] = {
      val binary = new Array[Byte](prefix.length + key.length)
      System.arraycopy(prefix, 0, binary, 0, prefix.length)
      System.arraycopy(key, 0, binary, prefix.length, key.length)
      binary
    }

    def get(key:Array[Byte]):Array[Byte] = root.get(join(key))

    def put(key:Array[Byte], value:Array[Byte]):Unit = root.put(join(key), value)

    def delete(key:Array[Byte]):Unit = root.delete(join(key))

    /**
      * Close operations on subsets have no effect.
      */
    def close():Unit = None

    def foreach(f:(Array[Byte], Array[Byte]) => Unit):Unit = root.foreach { (key, value) =>
      if(key.length >= prefix.length && prefix.indices.forall(i => prefix(i) == key(i))) {
        f(key.drop(prefix.length), value)
      }
    }

    override def subKVS(prefix:Array[Byte]):KeyValueStore = new SubKVS(root, join(prefix))
  }

}
