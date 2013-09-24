/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.lang.reflect.{Method, InvocationHandler}
import java.util.concurrent.Executor
import java.util.concurrent.atomic.{AtomicReference, AtomicInteger}
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import org.slf4j.LoggerFactory

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Session
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 * @param name このセッションの名前
 * @param executor このセッション上での RPC 処理を実行するためのスレッドプール
 */
class Session(val name:String, executor:Executor, service:Object, wire:Wire) {

	import Session.logger

	private[this] val pipeIdMask:Short = if(wire.isServer) Pipe.UNIQUE_MASK else 0
	private[this] val pipeSequence = new AtomicInteger(0)
	private[this] val pipes = new AtomicReference[Map[Short, Pipe]](Map())

	@volatile
	private[this] var stub = new Stub(service)

	/**
	 * セッションがクローズされたときに呼び出されるイベントハンドラ。
	 */
	val onClosed = new EventHandlers[Session]()

	/**
	 * このセッション上でピアに提供するサービス (function の集合) を設定。
	 */
	def service_=(service:Object) = stub = new Stub(service)

	// ワイヤーにメッセージが到着した時にメッセージをディスパッチ
	wire.onReceive(m => dispatch(m))

	// ==============================================================================================
	// パイプのオープン
	// ==============================================================================================
	/**
	 * このセッション上で指定された function とのパイプを作成します。
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
	 * 指定されたメッセージを受信したときに呼び出されます。
	 */
	private[this] def dispatch(frame:Message):Unit = {
		if(logger.isTraceEnabled){
			logger.trace(s"-> $frame")
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

	/**
	 * ピアから指定された Open メッセージを受信したときに呼び出されます。
	 */
	@tailrec
	private[this] def create(open:Open):Option[Pipe] = {
		val map = pipes.get()
		if(map.contains(open.pipeId)){
			post(Close(open.pipeId, null, s"duplicate pipe-id specified: ${open.pipeId}"))
			return None
		}
		val pipe = new Pipe(open.pipeId, this)
		if(pipes.compareAndSet(map, map + (pipe.id -> pipe))){
			return Some(pipe)
		}
		create(open)
	}

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

	/**
	 * 指定されたパイプがクローズされたときに呼び出されます。
	 */
	@tailrec
	private[asterisk] final def destroy(pipeId:Short):Unit = {
		val map = pipes.get()
		if(! pipes.compareAndSet(map, map - pipeId)){
			destroy(pipeId)
		}
	}

	/**
	 * ピアに対して指定されたメッセージを送信します。
	 */
	private[asterisk] def post(frame:Message):Unit = {
		wire.send(frame)
		logger.trace(s"<- $frame")
	}

	/**
	 * このセッションをクローズします。
	 */
	def close():Unit = {
		logger.trace(s"close():$name")
		// TODO 実装まだ

		wire.close()

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

		private[this] val functions = service.getClass.getInterfaces.map{ i =>
			i.getDeclaredMethods.collect {
				case m if m.getAnnotation(classOf[Export]) != null =>
					val id = m.getAnnotation(classOf[Export]).value()
					logger.debug(s"  function $id to ${m.getSimpleName}")
					id -> m
			}
		}.flatten.toMap

		def call(pipe:Pipe, open:Open):Unit = {
			functions.get(open.function) match {
				case Some(method) =>
					val r = new Runnable {
						def run() {
							Session.sessions.set(Session.this)
							Pipe.pipes.set(pipe)
							try {
								val params = Session.mapParameters(method.getParameterTypes, open.params:_*)
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
					executor.execute(r)   // TODO アノテーションで別スレッドか同期実行かを選べるようにしたい
				case None =>
					logger.debug(s"unexpected function call: ${open.function} is not defined on class ${service.getClass.getSimpleName}")
					pipe.close(new NoSuchMethodException(open.function.toString))
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

		def invoke(proxy:Any, method:Method, args:Array[AnyRef]):AnyRef = {
			val export = method.getAnnotation(classOf[Export])
			if(export == null){
				// toString() や hashCode() など Object 型のメソッド呼び出し
				method.invoke(this, args:_*)
			} else {
				val pipe = open(export.value(), args:_*)
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

	// ==============================================================================================
	// 型変換
	// ==============================================================================================
	/**
	 * 呼び出しパラメータを指定された型に適切な値へ変換します。
	 * @param types 想定する呼び出しパラメータの型
	 * @param params リモートから渡された呼び出しパラメータ値
	 * @return 呼び出しに使用するパラメータ値
	 */
	private[Session] def mapParameters(types:Array[Class[_]], params:AnyRef*):Array[Object] = {
		types.zip(params).map{ case (t, v) => TypeMapper.appropriateValue(v, t).asInstanceOf[Object] }.toList.toArray
	}

}
