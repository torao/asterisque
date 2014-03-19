/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import com.kazzla.asterisk.codec.{MsgPackCodec, Codec}
import com.kazzla.asterisk.netty.Netty
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import org.slf4j.LoggerFactory
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.util.Failure
import scala.util.Success

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Node
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * アプリケーションの実行環境となるノードを表します。
 *
 * @author Takami Torao
 */
class Node private[Node](name:String, initService:Service, bridge:Bridge, codec:Codec){
	import Node._

	/**
	 * このノード上で新しいセッションが発生した時に初期状態で使用するサービス。
	 */
	@volatile
	private[this] var _service = initService

	/**
	 * このノード上で Listen しているすべてのサーバ。ノードのシャットダウン時にクローズされる。
	 */
	private[this] val servers = new AtomicReference(Seq[Server]())

	/**
	 * このノード上で使用されているすべてのセッション。ノードのシャットダウン時にクローズされる。
	 */
	private[this] val _sessions = new AtomicReference(Seq[Session]())

	/**
	 * このノード上で新しいセッションが発生した時のデフォルトのサービスを変更します。
	 * @param newService 新しいサービス
	 * @return 現在設定されているサービス
	 */
	def service_=(newService:Service):Service = {
		val old = _service
		_service = newService
		old
	}

	/**
	 * このノード上で有効なすべてのセッションを参照します。
	 */
	def sessions:TraversableOnce[Session] = _sessions.get()

	// ==============================================================================================
	// 接続受け付けの開始
	// ==============================================================================================
	/**
	 * このノード上でリモートのノードからの接続を受け付けを開始します。アプリケーションは返値の [[Server]] を使用し
	 * てこの呼び出しで開始した接続受け付けを終了することが出来ます。
	 *
	 * アプリケーションは `onAccept` に指定した処理で新しい接続を受け付けセッションが発生した時の挙動を実装すること
	 * が出来ます。
	 *
	 * @param address バインドアドレス
	 * @param tls 通信に使用する SSLContext
	 * @param onAccept 新規接続を受け付けた時に実行する処理
	 * @return Server の Future
	 */
	def listen(address:SocketAddress, tls:Option[SSLContext] = None)(implicit onAccept:(Session)=>Unit = {_ => None}):Future[Server] = {
		import ExecutionContext.Implicits.global
		val promise = Promise[Server]()
		bridge.listen(codec, address, tls){ wire => onAccept(bind(wire)) }.onComplete {
			case Success(server) =>
				add(servers, server)
				promise.success(new Server(server.address){
					override def close(){
						remove(servers, server)
						server.close()
					}
				})
			case Failure(ex) => promise.failure(ex)
		}
		promise.future
	}

	// ==============================================================================================
	// ノードへの接続
	// ==============================================================================================
	/**
	 * このノードから指定されたアドレスの別のノードへ接続を行います。
	 *
	 * @param address 接続するノードのアドレス
	 * @param tls 通信に使用する SSLContext
	 * @return 接続により発生した Session の Future
	 */
	def connect(address:SocketAddress, tls:Option[SSLContext] = None):Future[Session] = {
		import ExecutionContext.Implicits.global
		val promise = Promise[Session]()
		bridge.connect(codec, address, tls).onComplete{
			case Success(wire) => promise.success(bind(wire))
			case Failure(ex) => promise.failure(ex)
		}
		promise.future
	}

	// ==============================================================================================
	// セッションの構築
	// ==============================================================================================
	/**
	 * 指定された Wire 上で新規のセッションを構築しメッセージング処理を開始します。
	 * このメソッドを使用することで `listen()`, `connect()` によるネットワーク以外の `Wire` 実装を使用すること
	 * が出来ます。
	 * @param wire セッションに結びつける Wire
	 * @return 新規セッション
	 */
	def bind(wire:Wire):Session = {
		logger.trace(s"bind($wire):$name")
		val s = new Session(this, s"$name[${wire.peerName}]", _service, wire)
		add(_sessions, s)
		s.onClosed ++ { session => remove(_sessions, session) }
		s
	}

	// ==============================================================================================
	// ノードのシャットダウン
	// ==============================================================================================
	/**
	 * このノードの処理を終了します。ノード上でアクティブなすべてのサーバ及びセッションがクローズされます。
	 */
	def shutdown():Unit = {
		servers.get().foreach{ _.close() }
		_sessions.get().foreach{ _.close() }
		logger.debug(s"shutting-down $name; all available ${_sessions.get().size} sessions, ${servers.get().size} servers are closed")
	}

}

object Node {
	private[Node] val logger = LoggerFactory.getLogger(classOf[Node])

	/**
	 * 新規のノードを生成するためのビルダーを作成します。
	 * @param name 新しく作成するノードの名前
	 */
	def apply(name:String):Builder = new Builder(name)

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// Builder
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * 新規のノードを構築するためのビルダークラスです。
	 */
	class Builder private[Node](name:String) {
		import ExecutionContext.Implicits.global
		private var service:Service = new Service {}
		private var bridge:Bridge = Netty
		private var codec:Codec = MsgPackCodec

		/**
		 * 新しく生成するノードが使用するブリッジを指定します。
		 */
		def bridge(bridge:Bridge):Builder = {
			this.bridge = bridge
			this
		}

		/**
		 * ノードが接続を受け新しいセッションの発生した初期状態でリモートのピアに提供するサービスを指定します。
		 * このサービスはセッション構築後にセッションごとに変更可能です。
		 * @param service 初期状態のサービス
		 */
		def serve(service:Service):Builder = {
			this.service = service
			this
		}

		/**
		 * 新しく生成するノードが使用するコーデックを指定します。
		 */
		def codec(codec:Codec):Builder = {
			this.codec = codec
			this
		}

		/**
		 * このビルダーに設定されている内容で新しいノードのインスタンスを構築します。
		 */
		def build():Node = new Node(name, service, bridge, codec)

	}

	/**
	 * 指定されたコンテナに要素を追加するための再帰関数。
	 */
	@tailrec
	private[Node] def add[T](container:AtomicReference[Seq[T]], element:T):Unit = {
		val n = container.get()
		if(! container.compareAndSet(n, n.+:(element))){
			add(container, element)
		}
	}

	/**
	 * 指定されたコンテナから要素を除去するための再帰関数。
	 */
	@tailrec
	private[Node] def remove[T](container:AtomicReference[Seq[T]], element:T):Unit = {
		val n = container.get()
		if(! container.compareAndSet(n, n.filter{ _ != element })){
			remove(container, element)
		}
	}

}
