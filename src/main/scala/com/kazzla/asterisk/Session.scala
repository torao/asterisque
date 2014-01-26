/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference, AtomicInteger}
import scala.annotation.tailrec
import org.slf4j.LoggerFactory
import java.io.IOException
import scala.concurrent.{Await, Promise}
import java.lang.reflect.{Method, InvocationHandler}
import scala.concurrent.duration.Duration

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Session
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 * @param name このセッションの名前
 */
class Session(val name:String, defaultService:Service, val wire:Wire) {

	import Session.logger

	@volatile
	private[this] var _service = defaultService

	def service_=(s:Service):Service = {
		val old = _service
		_service = s
		old
	}

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
	 * このセッションがクローズされているかを表すフラグ。
	 */
	private[this] val closed = new AtomicBoolean(false)

	/**
	 * このセッション上で新しい接続を受け付けた時に呼び出されるイベントハンドラです。
	 */
	val onAccept = new EventHandlers[(Pipe,Open)]()

	/**
	 * このセッションがクローズされたときに呼び出されるイベントハンドラです。
	 */
	val onClosed = new EventHandlers[Session]()

	/**
	 * セッションに設定できる任意の値。
	 */
	private[this] val attribute = new AtomicReference[Map[String,Any]](Map())

	wire.onReceive ++ dispatch        	// `Wire` にメッセージが到着した時のディスパッチャーを設定
	wire.onClosed ++ { _ => close() } 	// `Wire` がクローズされたときにセッションもクローズ
	wire.start()                      	// メッセージポンプを開始

	// ==============================================================================================
	// セッション値の設定
	// ==============================================================================================
	/**
	 * このセッションに任意の値を関連づけます。
	 * @param name 値の名前
	 * @param obj 設定する値
	 * @return 置き換えられる前の値
	 */
	def setAttribute(name:String, obj:Any):Option[Any] = {
		@tailrec
		def set():Option[Any] = {
			val map = attribute.get()
			val old = map.get(name)
			if(attribute.compareAndSet(map, map.updated(name, obj))){
				old
			} else {
				set()
			}
		}
		set()
	}

	// ==============================================================================================
	// セッション値の参照
	// ==============================================================================================
	/**
	 * このセッションに関連づけられている値を参照します。
	 * @param name 値の名前
	 * @return 値
	 */
	def getAttribute(name:String):Option[Any] = attribute.get().get(name)

	// ==============================================================================================
	// パイプのオープン
	// ==============================================================================================
	/**
	 * このセッション上のピアに対して指定された function との非同期呼び出しのためのパイプを作成します。
	 *
	 * @param function function の識別子
	 * @return function とのパイプ
	 */
	def open[T](function:Short):Pipe.Builder = new Pipe.Builder(this, function)

	/**
	 * 指定されたメッセージを受信したときに呼び出されその種類によって処理を分岐します。
	 */
	private[this] def dispatch(frame:Message):Unit = {
		if(logger.isTraceEnabled){
			logger.trace(s"dispatch:$frame")
		}
		val pipe = frame match {
			case open:Open => create(open)
			case _ => pipes.get().get(frame.pipeId)
		}
		pipe match {
			case Some(p) =>
				try {
					Session.using(this, p){
						_service.dispatch(p, frame)
					}
				} catch {
					case ex:Throwable =>
						logger.error(s"unexpected error: $frame, closing pipe", ex)
						post(Close[Any](frame.pipeId, null, s"internal error"))
						if(ex.isInstanceOf[ThreadDeath]){
							throw ex
						}
				}
			case None =>
				logger.debug(s"unknown pipe-id: $frame")
				post(Close[Any](frame.pipeId, null, s"unknown pipe-id: ${frame.pipeId}"))
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
			post(Close[Any](open.pipeId, null, s"duplicate pipe-id specified: ${open.pipeId}"))
			return None
		}
		// 新しいパイプを構築して登録
		val pipe = new Pipe(open.pipeId, open.function, this)
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
	private[asterisk] final def create(function:Short, params:Seq[Any]):Pipe = {
		val map = pipes.get()
		val id = ((pipeSequence.getAndIncrement & 0x7FFF) | pipeIdMask).toShort
		if(! map.contains(id)){
			val pipe = new Pipe(id, function, this)
			if(pipes.compareAndSet(map, map + (pipe.id -> pipe))){
				return pipe
			}
		}
		create(function, params)
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

	// ==============================================================================================
	// リモートインターフェースの参照
	// ==============================================================================================
	/**
	 * このセッションの相手側となるインターフェースを参照します。
	 */
	def bind[T](clazz:Class[T]):T = {
		clazz.cast(java.lang.reflect.Proxy.newProxyInstance(
			Thread.currentThread().getContextClassLoader,
			Array(clazz), new Skeleton(clazz)
		))
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
				logger.debug(s"normal method: ${method.getSimpleName}")
				method.invoke(this, args:_*)
			} else {
				val promise = Promise[Any]()
				open[Any](export.value()).onBlock{ block =>
				// ignore unreceiveable blocks
					None
				}.onSuccess{ result =>
					promise.success(result)
				}.onFailure{ ex =>
					promise.failure(ex)
				}.call((if(args==null) Array[AnyRef]() else args):_*)

				Await.result(promise.future, Duration.Inf).asInstanceOf[AnyRef]
			}
		}
	}

}

object Session {
	private[Session] val logger = LoggerFactory.getLogger(classOf[Session])

	private[this] val sessions = new ThreadLocal[Session]()

	def apply():Option[Session] = Option(sessions.get())

	private[asterisk] def using[T](session:Session, pipe:Pipe)(f: =>T):T = {
		val old = sessions.get()
		sessions.set(session)
		try {
			Pipe.using(pipe)(f)
		} finally {
			sessions.set(old)
		}
	}

	/*
	class AsyncCall private[Session](useStream:Boolean) {
		private[this] val prematureBlocks = new LinkedBlockingQueue[Block]()
		private[Session] val promise = Promise[Any]()
		private[Session] var _pipe:Pipe = _
		val result = promise.future
		def pipe = _pipe

		private[Session] def block(b:Block):Unit = if(useStream){
			prematureBlocks.synchronized {
				_onBlock match {
					case Some(f) => f(b)
					case None => prematureBlocks.put(b)
				}
			}
		}
		private[this] var _onBlock:Option[(Block)=>Unit] = None
		def onBlock(f:(Block)=>Unit) = {
			prematureBlocks.synchronized {
				_onBlock match {
					case Some(_) =>
						throw new IllegalStateException("onBlock handler is already set")
					case None =>
						if(! useStream){
							throw new IllegalStateException(s"cannot set onBlock handler for a method annotated with @Export(stream=false)")
						}
						_onBlock = Some({ block =>
							try { f(block) } catch {
								case ex:ThreadDeath => throw ex
								case ex:Throwable =>
									logger.error(s"fail to callback for block received: ${f.toString()}", ex)
							}
						})
						while(! prematureBlocks.isEmpty){
							_onBlock.get.apply(prematureBlocks.take())
						}
				}
			}
		}
	}
	*/

}
