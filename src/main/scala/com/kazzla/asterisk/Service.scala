/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import java.lang.reflect.Method
import java.nio.ByteBuffer

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Service
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * サブクラスの実装により通信相手の [[com.kazzla.asterisk.Node]] に対して提供するサービスを定義します。
 *
 * サービスのインターフェースは非同期処理として実装されます。具体的にはアノテーション
 * @@[[com.kazzla.asterisk.Export]] に function 番号を指定し、`Future` 型を返値とするメソッドをサブクラスで
 * 実装することでリモート呼び出しを可能にします。
 * {{{
 *   // インターフェース定義
 *   trait Reverse {
 *     @@Export(10)
 *     def reverse(text:String):Future[String]
 *   }
 *
 *   // サーバ側
 *   class ReverseService extends Service with Reverse {
 *     def reverse(text:String) = {
 *       Future(new String(text.toCharArray.reverse))
 *     }
 *   }
 *   Node("reverse").serve(new ReverseService()).build()
 *
 *   // クライアント側
 *   remote = session.bind(classOf[Reverse])
 *   remote.reverse("hello, world") match {
 *     case Some(result) => System.out.println(result)   // dlrow ,olleh
 *     case Failure(ex) => ex.printStackTrace()
 *   }
 * }}}
 *
 * もう一つの方法としてファンクション番号を指定して処理を実装する方法があります。この方法によって動的な定義に
 * 対応することが出来ますが型安全ではありません。
 * {{{
 *   // サーバ側
 *   class MyAsyncService extends Service {
 *     10 accept { args => Future("input: " + args.head) }
 *     20 accept { args => ... }
 *   }
 *   // クライアント側
 *   session.open(10, "ABS").onSuccess{ result =>
 *     System.out.println(result)   // input: ABC
 *   }.onFailure{ ex =>
 *     ex.printStackTrace()
 *   }
 * }}}
 *
 * インターフェースの実装メソッド及び accept に指定するラムダはメッセージの順序性を保証するために単一スレッドで
 * 呼び出されます。このためメソッド内で時間のかかる処理を行うとスレッドプールを共有するすべてのセッションの処理に
 * 影響を与えます。直ちに結果が確定しない処理を実装する場合は `scala.concurrent.future` などを使用して非同期
 * 化を行ってください。
 *
 * @author Takami Torao
 */
abstract class Service(context:ExecutionContext) {
	import Service._
	private[this] implicit val _context = context

	logger.debug(s"binding ${getClass.getSimpleName} as service")

	/**
	 * このサービスに定義されているファンクションのマップ。
	 */
	private[this] var functions = Map[Short,Function]()

	// ============================================================================================
	// パイプの参照
	// ============================================================================================
	/**
	 * 現在の function 処理を実行しているパイプを参照します。実行スレッドが function の呼び出し処理でない場合は
	 * 直ちに fail した `Future` を返しラムダは実行されません。
	 * このメソッドは利用可能なパイプを引数のラムダに渡し、ラムダが返す `Future` を返値とします。
	 * 相手の呼び出しのためにセッションが必要な場合は [[Pipe.session]] で参照することが出来ます。
	 */
	protected def withPipe[T](f:(Pipe)=>Future[T]):Future[T] = Pipe() match {
		case Some(pipe) => f(pipe)
		case None =>
			Future.failed(new Exception(s"pipe can only refer in caller thread of function"))
	}

	// ============================================================================================
	// function の定義
	// ============================================================================================
	/**
	 * サブクラスから function 番号が定義されているメソッドを抽出し同期呼び出し用に定義。
	 */
	private[this] def declare(export:Export, m:Method):Unit = {
		if(m.getReturnType != classOf[Future[_]]){
			throw new IllegalArgumentException(
				s"method with @Export annotation must have Future return type: ${m.getSimpleName}")
		}
		val id = export.value()
		logger.debug(s"  function $id to ${m.getSimpleName}")
		id.toInt accept { args =>
			try {
				val params = TypeMapper.appropriateValues(args, m.getParameterTypes)
				m.invoke(Service.this, params:_*).asInstanceOf[Future[Any]]
			} catch {
				case ex:Throwable =>
					logger.error(s"cannot invoke service: ${m.getSimpleName}, with parameter ${debugString(args)}")
					throw ex
			}
		}
	}

	// ============================================================================================
	// function の定義
	// ============================================================================================
	/**
	 * スーパークラス、スーパーインターフェース、自クラスから @Export 宣言されているメソッドを抽出し呼び出し用に
	 * 定義する。
	 */
	private[this] def declare(c:Class[_]):Unit = {
		c.getMethods.filter{ _.getAnnotation(classOf[Export]) != null }.foreach { m =>
			declare(m.getAnnotation(classOf[Export]), m)
		}
		if(c.getSuperclass != null){
			declare(c.getSuperclass)
		}
		c.getInterfaces.foreach{ declare }
	}

	// このインスタンスに対するファンクションの定義
	declare(getClass)

	// ==============================================================================================
	// メッセージ処理の実行
	// ==============================================================================================
	/**
	 * 指定されたメッセージに対する非同期処理を実行します。
	 * @param pipe パイプ
	 * @param msg 受信したメッセージ
	 */
	private[asterisk] def dispatch(pipe:Pipe, msg:Message):Unit = msg match {
		case Open(_, _, params) =>
			functions.get(pipe.function) match {
				case Some(func) =>
					pipe.future.onComplete {
						case Success(result) => func.disconnect(result, null)
						case Failure(ex) => func.disconnect(null, ex.toString)
					}
					func(params).onComplete {
						case Success(result) =>
							pipe.close(result)
						case Failure(ex) =>
							logger.error(s"unexpected exception: $ex", ex)
							pipe.close(ex)
					}
				case None =>
					logger.debug(s"function unbound on: ${pipe.function}, $pipe, $msg")
					pipe.close(new RemoteException(s"function unbound on: ${pipe.function}"))
			}
		case block:Block => pipe.onBlock(block)
		case close:Close => pipe.close(close)
	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// FunctionIdentifier
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * サブクラスでサービスのファンクションを DSL として記述するためのクラスです。
	 * @param function ファンクション番号
	 */
	protected implicit class FunctionIdentifier(function:Int){
		if(function.toShort != function){
			throw new IllegalStateException(s"function id out of range for Short: $function")
		}

		// ============================================================================================
		// 呼び出し処理の設定
		// ============================================================================================
		/**
		 * このファンクション番号に対する処理を指定します。
		 *
		 * @param f 処理
		 * @return 関数定義
		 */
		def accept(f:(Seq[Any])=>Future[Any]):Function = {
			if(functions.contains(function.toShort)){
				throw new IllegalArgumentException(s"function $function already defined")
			}
			val func = new Function(f)
			functions = functions.updated(function.toShort, func)
			func
		}

	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// Function
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * サービスのファンクション番号に関連づけられている処理の実体。
	 * @param onAccept ファンクションが呼び出されたときに実行する処理
	 */
	class Function private[Service](onAccept:(Seq[Any])=>Future[Any]) {
		private[this] var _onDisconnect:Option[(Any)=>Unit] = None

		/**
		 * ピアによって切断されたときの処理を指定します。
		 */
		def disconnect(f:(Any)=>Unit):Function = {
			_onDisconnect = Some(f)
			this
		}

		private[Service] def apply(params:Seq[Any]):Future[Any] = onAccept(params)
		private[Service] def disconnect(result:Any, error:String) = _onDisconnect.foreach { _(result) }
	}

	/**
	 * ByteBuffer に関する拡張
	 * @param buffer ByteBuffer
	 */
	protected implicit class ByteBufferUtil(buffer:ByteBuffer){
		def getString(charset:String):String = if(buffer.isDirect){
			val b = new Array[Byte](buffer.remaining())
			buffer.get(b)
			new String(b, charset)
		} else {
			val str = new String(buffer.array(), buffer.arrayOffset(), buffer.remaining())
			buffer.position(buffer.position() + buffer.remaining())
			str
		}
	}

}

object Service {
	private[Service] val logger = LoggerFactory.getLogger(classOf[Service])
}
