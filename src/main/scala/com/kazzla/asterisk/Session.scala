/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.lang.reflect.{Method, InvocationHandler}
import java.util.concurrent.Executor
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference, AtomicInteger}
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import org.slf4j.LoggerFactory
import java.io.IOException

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Session
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 * @param name このセッションの名前
 * @param executor このセッション上での RPC 処理を実行するためのスレッドプール
 */
class Session(val name:String, executor:Executor, service:Object, val wire:Wire) {

	import Session._

	/**
	 * このセッションで生成するパイプの ID に付加するビットマスク。
	 */
	private[this] val pipeIdMask:Short = if(wire.isServer) Pipe.UNIQUE_MASK else 0

	/**
	 * パイプ ID を発行するためのシーケンス。
	 */
	private[this] val pipeSequence = new AtomicInteger(0)

	/**
	 * このセッション上でオープンされているパイプ。
	 */
	private[this] val pipes = new AtomicReference[Map[Short, Pipe]](Map())

	/**
	 * このセッション上で相手側に提供しているサービスのスタブ。
	 */
	@volatile
	private[this] var stub = new Stub(service)

	/**
	 * このセッションがクローズされているかを表すフラグ。
	 */
	private[this] val closed = new AtomicBoolean(false)

	/**
	 * このセッションがクローズされたときに呼び出されるイベントハンドラです。
	 */
	val onClosed = new EventHandlers[Session]()

	/**
	 * このセッション上でピアに提供するサービス (function の集合) を設定します。
	 */
	def service_=(service:Object) = stub = new Stub(service)

	wire.onReceive ++ dispatch        	// `Wire` にメッセージが到着した時のディスパッチャーを設定
	wire.onClosed ++ { _ => close() } 	// `Wire` がクローズされたときにセッションもクローズ
	wire.start()                      	// メッセージポンプを開始


	// ==============================================================================================
	// パイプのオープン
	// ==============================================================================================
	/**
	 * このセッション上のピアに対して指定された function とのパイプを作成します。
	 * @param function function の識別子
	 * @param params function の実行パラメータ
	 * @return function とのパイプ
	 */
	def open(function:Short, params:AnyRef*):Pipe = {
		val pipe = create(function, params:_*)
		post(Open(pipe.id, function, params:_*))
		pipe
	}

	// ==============================================================================================
	// リモートインターフェースの参照
	// ==============================================================================================
	/**
	 * このセッションの相手側となるインターフェースを参照します。
	 */
	def getRemoteInterface[T](clazz:Class[T]):T = {
		clazz.cast(java.lang.reflect.Proxy.newProxyInstance(
			Thread.currentThread().getContextClassLoader,
			Array(clazz), new Skeleton(clazz)
		))
	}

	/**
	 * 指定されたメッセージを受信したときに呼び出されその種類によって処理を分岐します。
	 */
	private[this] def dispatch(frame:Message):Unit = {
		if(logger.isTraceEnabled){
			logger.trace(s"dispatch:$frame")
		}
		frame match {
			case open:Open =>
				create(open).foreach{ pipe => stub.call(pipe, open) }
			case close:Close[_] =>
				pipes.get().get(frame.pipeId) match {
					case Some(pipe) => pipe.close(close)
					case None => logger.debug(s"unknown pipe-id: $close")
				}
			case block:Block =>
				pipes.get().get(frame.pipeId) match {
					case Some(pipe) => pipe.receiveQueue.put(block)
					case None => logger.debug(s"unknown pipe-id: $block")
				}
		}
	}

	// ==============================================================================================
	// パイプの構築
	// ==============================================================================================
	/**
	 * ピアから受信した Open メッセージに対応するパイプを構築します。オープンに成功した場合は新しく構築されたパイプ
	 * を返します。
	 */
	@tailrec
	private[this] def create(open:Open):Option[Pipe] = {
		val map = pipes.get()
		// 既に使用されているパイプ ID が指定された場合はエラーとしてすぐ終了
		if(map.contains(open.pipeId)){
			logger.debug(s"duplicate pipe-id specified: ${open.pipeId}")
			post(Close(open.pipeId, null, s"duplicate pipe-id specified: ${open.pipeId}"))
			return None
		}
		// 新しいパイプを構築して登録
		val pipe = new Pipe(open.pipeId, this)
		if(pipes.compareAndSet(map, map + (pipe.id -> pipe))){
			return Some(pipe)
		}
		create(open)
	}

	// ==============================================================================================
	// パイプの構築
	// ==============================================================================================
	/**
	 * ピアに対して Open メッセージを送信するためのパイプを生成します。
	 */
	@tailrec
	private[this] def create(function:Short, params:AnyRef*):Pipe = {
		val map = pipes.get()
		val id = ((pipeSequence.getAndIncrement & 0x7FFF) | pipeIdMask).toShort
		if(! map.contains(id)){
			val pipe = new Pipe(id, this)
			if(pipes.compareAndSet(map, map + (pipe.id -> pipe))){
				return pipe
			}
		}
		create(function, params:_*)
	}

	// ==============================================================================================
	// パイプの破棄
	// ==============================================================================================
	/**
	 * このセッションが保持しているパイプのエントリから該当するパイプを切り離します。
	 */
	@tailrec
	private[asterisk] final def destroy(pipeId:Short):Unit = {
		val map = pipes.get()
		if(! pipes.compareAndSet(map, map - pipeId)){
			destroy(pipeId)
		}
	}

	// ==============================================================================================
	// メッセージの送信
	// ==============================================================================================
	/**
	 * ピアに対して指定されたメッセージを送信します。
	 */
	@volatile
	private[asterisk] var post:(Message)=>Unit = { frame =>
		wire.send(frame)
		if(logger.isTraceEnabled){
			logger.trace(s"post:$frame")
		}
	}

	// ==============================================================================================
	// セッションのクローズ
	// ==============================================================================================
	/**
	 * このセッションをクローズします。
	 */
	def close():Unit = if(closed.compareAndSet(false, true)){
		logger.trace(s"close():$name")

		// 以降のメッセージ送信をすべて例外に変更して送信を終了
		post = { _ => throw new IOException(s"session $name closed") }

		// 残っているすべてのパイプに Close メッセージを送信
		pipes.get().values.foreach{ _.close(new IOException(s"session $name closed")) }

		// Wire のクローズ
		wire.close()

		// セッションのクローズを通知
		onClosed(this)
	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// Stub
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * リフレクションを使用してサービスのメソッド呼び出しを行うためのクラス。
	 */
	private[this] class Stub(service:AnyRef) {

		import scala.language.reflectiveCalls

		logger.debug(s"binding ${service.getClass.getSimpleName} as service of $name")

		/**
		 * サービスインスタンスから抽出した function 番号が定義されているメソッドのマップ。
		 */
		private[this] val functions = service.getClass.getInterfaces.map{ i =>
			i.getDeclaredMethods.collect {
				case m if m.getAnnotation(classOf[Export]) != null =>
					val id = m.getAnnotation(classOf[Export]).value()
					logger.debug(s"  function $id to ${m.getSimpleName}")
					id -> m
			}
		}.flatten.toMap

		// ============================================================================================
		// function の呼び出し
		// ============================================================================================
		/**
		 * 指定されたパイプを使用して function の呼び出しを行います。
		 * @param pipe 呼び出しに使用するパイプ
		 * @param open 呼び出しの Open メッセージ
		 */
		def call(pipe:Pipe, open:Open):Unit = functions.get(open.function) match {
			case Some(method) =>
				// TODO アノテーションで別スレッドか同期実行かを選べるようにしたい
				executor.execute(new Runnable {
					def run() = call(pipe, open, method)
				})
			case None =>
				logger.debug(s"unexpected function call: ${open.function} is not defined on class ${service.getClass.getSimpleName}")
				pipe.close(new NoSuchMethodException(open.function.toString))
		}

		// ============================================================================================
		// function の呼び出し
		// ============================================================================================
		/**
		 * 指定されたパイプを使用して function の呼び出しを行います。
		 * @param pipe 呼び出しに使用するパイプ
		 * @param open 呼び出しの Open メッセージ
		 * @param method 呼び出し対象のメソッド
		 */
		private[this] def call(pipe:Pipe, open:Open, method:Method)():Unit = {
			Session.sessions.set(Session.this)
			Pipe.pipes.set(pipe)
			try {

				// メソッドのパラメータを適切な型に変換
				val params = method.getParameterTypes.zip(open.params).map{ case (t, v) =>
					TypeMapper.appropriateValue(v, t).asInstanceOf[Object]
				}.toList.toArray

				// メソッドの呼び出し
				val result = method.invoke(service, params:_*)
				pipe.close(result)

			} catch {
				case ex:Throwable =>
					logger.debug(s"on call ${method.getSimpleName} with parameter ${open.params}", ex)
					pipe.close(ex)
			} finally {
				Pipe.pipes.remove()
				Session.sessions.remove()
			}
		}

	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// Skeleton
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * リモート呼び出し先の function を @Export 定義されたメソッドとして扱うための動的プロキシ用ハンドラ。
	 */
	private[this] class Skeleton(clazz:Class[_]) extends InvocationHandler {

		// 指定されたインターフェースのすべてのメソッドに @Export アノテーションが付けられていることを確認
		{
			import scala.language.reflectiveCalls
			val m = clazz.getDeclaredMethods.filter{ m => m.getAnnotation(classOf[Export]) == null }.map{ _.getSimpleName }
			if(m.size > 0){
				throw new IllegalArgumentException(
					s"@${classOf[Export].getSimpleName} annotation is not specified on: ${m.mkString(",")}")
			}
		}

		// ============================================================================================
		// リモートメソッドの呼び出し
		// ============================================================================================
		/**
		 * リモートメソッドを呼び出します。
		 * @param proxy プロキシオブジェクト
		 * @param method 呼び出し対象のメソッド
		 * @param args メソッドの引数
		 * @return 返し値
		 */
		def invoke(proxy:Any, method:Method, args:Array[AnyRef]):AnyRef = {
			val export = method.getAnnotation(classOf[Export])
			if(export == null){
				// toString() や hashCode() など Object 型のメソッド呼び出し
				method.invoke(this, args:_*)
			} else {
				val pipe = open(export.value(), (if(args==null) Array[AnyRef]() else args):_*)
				val close = Await.result(pipe.future, Duration.Inf)     // TODO アノテーションで呼び出しタイムアウトの設定
				if(close.errorMessage != null){
					throw new Session.RemoteException(close.errorMessage)
				}
				close.result.asInstanceOf[AnyRef]
			}
		}
	}

}

object Session {
	private[Session] val logger = LoggerFactory.getLogger(classOf[Session])

	/**
	 * サービスの呼び出し中にセッションを参照するためのスレッドローカル変数。
	 */
	private[Session] val sessions = new ThreadLocal[Session]()

	// ==============================================================================================
	// セッションの参照
	// ==============================================================================================
	/**
	 * 現在のスレッドを実行しているセッションを参照します。
	 * @return 現在のセッション
	 */
	def apply():Option[Session] = Option(sessions.get())

	class RemoteException(msg:String) extends RuntimeException(msg)

	/**
	 * メソッドからデバッグ用の名前を取得するための拡張。
	 * @param method メソッド
	 */
	private[Session] implicit class RichMethod(method:Method){
		def getSimpleName:String = {
			method.getDeclaringClass.getSimpleName + "." + method.getName + "(" + method.getParameterTypes.map { p =>
				p.getSimpleName
			}.mkString(",") + "):" + method.getReturnType.getSimpleName
		}
	}

}
