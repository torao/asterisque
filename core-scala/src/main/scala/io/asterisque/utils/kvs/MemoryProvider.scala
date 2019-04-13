package io.asterisque.utils.kvs

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

import io.asterisque.utils.KeyValueStore
import org.apache.commons.codec.binary.Hex

/**
  * JavaVM ヒープを使用する永続化されない KVS プロバイダ実装です。`kvs:memory:...` で使用することができます。
  */
class MemoryProvider extends KeyValueStoreProvider {
  override def getBuilder:PartialFunction[(String, URI), KeyValueStore] = {
    case (scheme, _) if scheme == "memory" || scheme == "mem" =>
      val instance = new ConcurrentHashMap[String, String]()
      new MemoryProvider.Storage(instance)
  }
}

private object MemoryProvider {

  private[MemoryProvider] class Storage(db:ConcurrentHashMap[String, String]) extends KeyValueStore {

    override def get(key:Array[Byte]):Array[Byte] = Option(db.get(encode(key))).map(x => decode(x)).orNull

    override def put(key:Array[Byte], value:Array[Byte]):Unit = db.put(encode(key), encode(value))

    override def delete(key:Array[Byte]):Unit = db.remove(encode(key))

    override def foreach(f:(Array[Byte], Array[Byte]) => Unit):Unit = {
      db.forEach((key, value) => f(decode(key), decode(value)))
    }

    override def close():Unit = None
  }

  private[this] def encode(b:Array[Byte]):String = Hex.encodeHexString(b)

  private[this] def decode(s:String):Array[Byte] = Hex.decodeHex(s)
}
