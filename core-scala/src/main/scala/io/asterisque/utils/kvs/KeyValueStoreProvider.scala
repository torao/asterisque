package io.asterisque.utils.kvs

import java.net.URI

import io.asterisque.utils.KeyValueStore

/**
  * URI に対して利用可能な KeyValueStore を作成するプロバイダインターフェースです。
  */
trait KeyValueStoreProvider {

  /**
    * URI に対する KeyValueStore を取得できる部分関数を参照します。部分関数は `(lower-case-scheme, uri)` を引数に取ります。
    * ここで `lower-case-scheme` は小文字で表された URI スキーマです。
    *
    * @return the partial function to build KeyValueStore
    */
  def getBuilder:PartialFunction[(String, URI), KeyValueStore]

}
