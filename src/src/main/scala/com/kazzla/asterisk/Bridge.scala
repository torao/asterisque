/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.net.SocketAddress
import javax.net.ssl.SSLContext
import scala.concurrent.Future
import java.io.Closeable
import com.kazzla.asterisk.codec.Codec

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Bridge
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * `Node` からネットワークに接続するための実装です。
 * 通常このクラスはスレッドやノード間で共有されるためサブクラスが状態を持つ場合は十分注意する必要があります。
 * @author Takami Torao
 */
trait Bridge {

	// ==============================================================================================
	// 接続の実行
	// ==============================================================================================
	/**
	 * 指定されたアドレスに対して非同期接続を行い `Wire` の Future を返します。
	 * @param codec Wire 上で使用するコーデック
	 * @param address 接続先のアドレス
	 * @param sslContext クライアント認証を行うための SSL 証明書 (Noneの場合は非SSL接続)
	 * @return Wire の Future
	 */
	def connect(codec:Codec, address:SocketAddress, sslContext:Option[SSLContext]):Future[Wire]

	// ==============================================================================================
	// 受付の実行
	// ==============================================================================================
	/**
	 * 指定されたアドレスに対して非同期で接続を受け付ける `Server` の Future を返します。
	 * @param codec Wire 上で使用するコーデック
	 * @param address バインド先のアドレス
	 * @param sslContext サーバ認証を行うための SSL 証明書 (Noneの場合は非SSL接続)
	 * @param onAccept サーバ上で新しい接続が発生した時のコールバック
	 * @return Server の Future
	 */
	def listen(codec:Codec, address:SocketAddress, sslContext:Option[SSLContext])(onAccept:(Wire)=>Unit):Future[Server]
}

class Server(val address:SocketAddress) extends Closeable {
	def close():Unit = None
}