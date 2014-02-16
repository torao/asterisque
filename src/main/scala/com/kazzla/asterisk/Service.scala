/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import org.slf4j.LoggerFactory
import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.util.{Failure, Success}
import java.lang.reflect.Method
import java.nio.ByteBuffer

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Service
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * サブクラスの実装により通信相手の [[com.kazzla.asterisk.Node]] に対して提供するサービスを定義します。
 *
 * サービスのインターフェースは非同期処理として実装されます。具体的には @@[[com.kazzla.asterisk.Export]] 宣言
 * された `Future` を返値とするメソッドをサブクラスで実装することでリモート呼び出しを可能にします。
 * {{{
 *   // インターフェース定義
 *   trait Reverse {
 *     @@Export(10)
 *     def reverse(text:String):Future[String]
 *   }
 *   // サーバ側
 *   class ReverseService extends Service with Reverse {
 *     def reverse(text:String) = {
 *       Promise.successful(new String(text.toCharArray.reverse)).future
 *     }
 *   }
 *   Node("reverse").serve(new ReverseService()).build()
 *   // クライアント側
 *   remote = session.bind(classOf[Reverse])
 *   System.out.println(remote.reverse("hello, world"))   // dlrow ,olleh
 * }}}
 *
 * もう一つの方法としてファンクション番号をしていして処理を実装する方法があります。この方法によって動的な定義に
 * 対応することが出来ますが型安全ではありません。
 * {{{
 *   // サーバ側
 *   class MyAsyncService extends Service {
 *     10 accept { args => Future("input: " + args.head) }
 *     20 accept { args => ... }
 *   }
 *   // クライアント側
 *   val pipe = session.open(10).onSuccess{ result =>
 *     System.out.println(result)   // input: ABC
 *   }.onFailure{ ex =>
 *     ex.printStackTrace()
 *   }.call("ABC")
 * }}}
 *
 * これらのメソッド及び accept 処理はメッセージの順序性を保証するために単一スレッドで呼び出されます。従ってメソッ
 * ド内で時間のかかる処理を行うとスレッドプールを共有するすべてのセッションの処理が停止します。
 * メソッド内からは `Session()`, `Pipe()` を使用してそれぞれこの呼び出しを行っているセッションとパイプに
 * アクセスすることが出来ます。ただしこれらはメソッド内から呼び出した別スレッドには伝播しません。
 * `Close` メッセージを受信した後は呼び出し後にフレームワークによってパイプがクローズされます。
 *
 * @author Takami Torao
 */
abstract class Service(implicit context:ExecutionContext) {
	import Service._

	/**
	 * このサービスに定義されているファンクションのマップ。
	 */
	private[this] var functions = Map[Short,Function]()

	protected def withPipe[T](f:(Pipe)=>Future[T]):Future[T] = Pipe() match {
		case Some(pipe) => f(pipe)
		case None =>
			Promise.failed(throw new Exception(s"pipe can only refer in caller thread of function")).future
	}

	logger.debug(s"binding ${getClass.getSimpleName} as service")

	// ============================================================================================
	// function の定義
	// ============================================================================================
	/**
	 * サブクラスから function 番号が定義されているメソッドを抽出し同期呼び出し用に定義。
	 */
	private[this] def declare(export:Export, m:Method):Unit = {
		if(m.getReturnType != classOf[Future[_]]){
			throw new IllegalArgumentException(s"method with @Export annotation must have Future return type: ${m.getSimpleName}")
		}
		val id = export.value()
		logger.debug(s"  function $id to ${m.getSimpleName}")
		id.toInt accept { args =>
			val params = TypeMapper.appropriateValues(args, m.getParameterTypes)
			m.invoke(Service.this, params:_*).asInstanceOf[Future[Any]]
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
