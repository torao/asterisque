/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import java.io.{IOException, InputStream, OutputStream}
import java.lang.reflect.Method

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
 *       Future(new String(text.toCharArray.reverse))
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
					pipe.onSuccess ++ { result => func.disconnect(result, null) }
					pipe.onFailure ++ { ex => func.disconnect(null, ex.toString) }
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
		case block:Block => pipe.block(block)
		case close:Close[_] => pipe.close(close)
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

	logger.debug(s"binding ${getClass.getSimpleName} as service")

	/**
	 * サブクラスから function 番号が定義されているメソッドを抽出し同期呼び出し用に定義。
	 */
	private[this] def declare(export:Export, m:Method):Unit = {
		if(m.getReturnType != classOf[Future[_]]){
			throw new IllegalArgumentException(s"method with @Export annotation must have Future return type: ${m.getSimpleName}")
		}
		val id = export.value()
		val stream = export.stream()
		logger.debug(s"  function $id to ${m.getSimpleName}${if(stream) s" (stream)" else ""}")
		id.toInt accept {args =>
			val pipe = Pipe().get
			pipe.useStream(pipe.prepareStream(stream)){
				val params = TypeMapper.appropriateValues(args, m.getParameterTypes)
				m.invoke(Service.this, params:_*).asInstanceOf[Future[Any]]
			}
		}
	}

	/**
	 * サブクラスから function 番号が定義されている静的なメソッドを抽出し同期呼び出し用に定義。
	 */
	getClass.getInterfaces.foreach{
		_.getDeclaredMethods.filter{ _.getAnnotation(classOf[Export]) != null }.foreach { m =>
			declare(m.getAnnotation(classOf[Export]), m)
		}
	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// PipeStream
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * サブクラスで実装している同期処理内から送受信するブロックをストリームとして参照することの出来るように
	 * [[com.kazzla.asterisk.Pipe]] を拡張します。入出力ストリームを参照するにはメソッドのエクスポート宣言で
	 * @[[com.kazzla.asterisk.Export]](value=nnn,stream=true) のように stream を true に設定する必要が
	 * あります。
	 *
	 * @param pipe 拡張するパイプ
	 */
	protected implicit class PipeStream(pipe:Pipe){
		private[Service] def prepareStream(us:Boolean):Option[(InputStream,OutputStream)] = if(us){
			val in = new PipeInputStream()
			val out = new PipeOutputStream(pipe)
			pipe.onBlock ++ in
			Some(in, out)
		} else {
			None
		}
		private[Service] def useStream[T](s:Option[(InputStream,OutputStream)])(f: =>T):T = {
			Service.stream.set(s)
			try {
				f
			} finally {
				Service.stream.set(null)
			}
		}

		// ==============================================================================================
		// 入力ストリーム
		// ==============================================================================================
		/**
		 * パイプによる `Block` 受信をストリームとして参照します。
		 * @@[[com.kazzla.asterisk.Export]](stream=true) 宣言されているメソッド内からのみ使用することが出来ます。
		 */
		def in:InputStream = Service.getStream._1

		// ==============================================================================================
		// 出力ストリーム
		// ==============================================================================================
		/**
		 * パイプを使用した `Block` 送信をストリームとして行います。
		 * @@[[com.kazzla.asterisk.Export]](stream=true) 宣言されているメソッド内からのみ使用することが出来ます。
		*/
		def out:OutputStream = Service.getStream._2

	}

}

object Service {
	private[Service] val logger = LoggerFactory.getLogger(classOf[Service])

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// Function
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * サービスのファンクション番号に関連づけられている処理の実体。
	 * @param onAccept ファンクションが呼び出されたときに実行する処理
	 */
	class Function private[Service](onAccept:(Seq[Any])=>Future[Any]) {

		/**
		 * ピアによって切断されたときに呼び出される処理。
		 */
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
	 * ストリームを保持するための ThreadLocal。
	 */
	private[Service] val stream = new ThreadLocal[Option[(InputStream,OutputStream)]]()

	// ============================================================================================
	// ストリームの参照
	// ============================================================================================
	/**
	 * 現在のスレッドからストリームを参照します。
	 * @return ストリーム
	 */
	private[Service] def getStream:(InputStream,OutputStream) = Option(Service.stream.get()) match {
		case Some(s) if s.isDefined => s.get
		case _ =>
			throw new IOException(s"function is not declared with @${classOf[Export].getSimpleName}(stream=true), or out of scope")
	}

}
