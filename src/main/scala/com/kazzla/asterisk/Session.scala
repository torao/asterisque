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
import java.lang.reflect.{Method, InvocationHandler}
import scala.concurrent.{Await, ExecutionContext, Promise, Future}
import scala.util.{Success, Failure}
import java.util.concurrent.Executor
import scala.concurrent.duration.Duration

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Session
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * ピアとの通信状態を表すクラスです。
 *
 * @author Takami Torao
 * @param name このセッションの名前
 * @param defaultService このセッション上でピアに公開する初期状態のサービス
 * @param wire このセッションのワイヤー
 * @param messagePump このセッションからメッセージを送信するためのスレッド
 */
class Session private[asterisk](val name:String, defaultService:Service, val wire:Wire, messagePump:Executor)
	extends Attributes
{
	import Session.logger

	/**
	 * このセッションで提供しているサービス。
	 */
	@volatile
	private[this] var _service = defaultService

	/**
	 * このセッション上でピアに公開するサービスを変更します。
	 * @param s 新しく公開するサービス
	 * @return 以前のサービス
	 */
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
	 * 新規のパイプ ID を発行するためのシーケンス番号。
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
	 * このセッションがクローズされたときに呼び出されるイベントハンドラです。
	 */
	val onClosed = new EventHandlers[Session]()

	// `Wire` とこのセッションを結合しメッセージポンプを開始
	wire.onReceive ++ dispatch        	// `Wire` にメッセージが到着した時のディスパッチャーを設定
	wire.onClosed ++ { _ => close() } 	// `Wire` がクローズされたときにセッションもクローズ
	wire.start()                      	// メッセージポンプを開始

	// ==============================================================================================
	// パイプのオープン
	// ==============================================================================================
	/**
	 * このセッション上のピアに対して指定された function との非同期呼び出しのためのパイプを作成します。
	 *
	 * @param function function の識別子
	 * @param params function の実行パラメータ
	 * @param onTransferComplete 呼び出し先とのパイプが生成されたときに実行される処理
	 * @return パイプに対する Future
	 */
	def open(function:Short, params:Any*)(implicit onTransferComplete:(Pipe)=>Future[Any] = { _.future }):Future[Any] = {
		import ExecutionContext.Implicits.global
		val promise = Promise[Future[Any]]()
		val pipe = create(function)
		pipe.open(params, { () =>
			Session.using(this, pipe){
				val future = onTransferComplete(pipe) andThen {
					case Success(result) =>
						if(! pipe.isClosed){
							pipe.close(result)
						}
					case Failure(ex) =>
						if(! pipe.isClosed){
							pipe.close(ex)
						}
				}
				promise.success(future)
			}
		})
		Await.result(promise.future, Duration.Inf)
	}

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
						post(Close(frame.pipeId, Left(s"internal error")), None)
						if(ex.isInstanceOf[ThreadDeath]){
							throw ex
						}
				}
			case None =>
				logger.debug(s"unknown pipe-id: $frame")
				frame match {
					case _:Close =>
						logger.debug(s"both of sessions unknown pipe #${frame.pipeId}")
					case _ =>
						post(Close(frame.pipeId, Left(s"unknown pipe-id: ${frame.pipeId}")), None)
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
			post(Close(open.pipeId, Left(s"duplicate pipe-id specified: ${open.pipeId}")), None)
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
	private[asterisk] final def create(function:Short):Pipe = {
		val map = pipes.get()
		val id = ((pipeSequence.getAndIncrement & 0x7FFF) | pipeIdMask).toShort
		if(! map.contains(id)){
			val pipe = new Pipe(id, function, this)
			if(pipes.compareAndSet(map, map + (pipe.id -> pipe))){
				return pipe
			}
		}
		create(function)
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
	private[asterisk] var post:(Message, Option[()=>Unit])=>Unit = { (frame, onSend) =>
		messagePump.execute(new Runnable {
			override def run(): Unit = {
				wire.send(frame)
				if(logger.isTraceEnabled){
					logger.trace(s"post:$frame")
				}
				onSend.foreach{ _() }
			}
		})
	}

	// ==============================================================================================
	// セッションのクローズ
	// ==============================================================================================
	/**
	 * このセッションをクローズします。
	 * 実行中のすべてのパイプはクローズされ、以後のメッセージ配信は行われなくなります。
	 */
	def close():Unit = if(closed.compareAndSet(false, true)){
		logger.trace(s"close():$name")

		// 残っているすべてのパイプに Close メッセージを送信
		pipes.get().values.foreach{ p => p.close(new IOException(s"session $name closed")) }

		// 以降のメッセージ送信をすべて例外に変更して送信を終了
		// ※Pipe#close() で Session#post() が呼び出されるためすべてのパイプに Close を投げた後に行う
		post = { (_,_) => throw new IOException(s"session $name closed") }

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
				// toString() や hashCode() など Object 型のメソッド呼び出し?
				logger.debug(s"normal method: ${method.getSimpleName}")
				method.invoke(this, args:_*)
			} else {
				// there is no way to receive block in interface binding
				open(export.value(), (if(args==null) Array[AnyRef]() else args):_*)
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
}
