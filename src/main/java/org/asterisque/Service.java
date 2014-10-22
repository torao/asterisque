/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

import org.asterisque.codec.TypeConversion;
import org.asterisque.msg.Open;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
 *   interface Reverse {
 *     @@Export(10)
 *     public CompletableFuture<String> reverse(String text);
 *   }
 *
 *   // サーバ側
 *   public class ReverseService extends Service with Reverse {
 *     public CompletableFuture<String> reverse(String text) {
 *       Future(new String(text.toCharArray.reverse))
 *     }
 *   }
 *   Node("reverse").serve(new ReverseService()).build()
 *
 *   // クライアント側
 *   remote = session.bind(Reverse.class)
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
public abstract class Service {
	private static final Logger logger = LoggerFactory.getLogger(Service.class);

	/**
	 * このサービスに定義されているファンクションのマップ。
	 */
	private final Map<Short,Function> functions = new HashMap<>();

	protected Service(){
		logger.debug("binding " + (getClass().isAnonymousClass()? "<anonymous>": getClass().getSimpleName()) + " as service");

		// このインスタンスに対するファンクションの定義
		declare(getClass());
	}

	// ============================================================================================
	// パイプの参照
	// ============================================================================================
	/**
	 * 現在の function 処理を実行しているパイプを参照します。実行スレッドが function の呼び出し処理でない場合は
	 * 直ちに fail した `Future` を返しラムダは実行されません。
	 * このメソッドは利用可能なパイプを引数のラムダに渡し、ラムダが返す `Future` を返値とします。
	 * 相手の呼び出しのためにセッションが必要な場合は [[Pipe.session]] で参照することが出来ます。
	 */
	protected <T> CompletableFuture<T> withPipe(java.util.function.Function<Pipe,CompletableFuture<T>> f){
		return Pipe.orElse(() -> {
			CompletableFuture<T> future = new CompletableFuture<>();
			future.completeExceptionally(new Exception("pipe can only refer in caller thread of function"));
			return future;
		}, f);
	}

	// ============================================================================================
	// function の定義
	// ============================================================================================
	/**
	 * サブクラスから function 番号が定義されているメソッドを抽出し同期呼び出し用に定義。
	 */
	private void declare(Export export, Method m){
		if(m.getReturnType() != CompletableFuture.class){
			throw new IllegalArgumentException(
				"method with @Export annotation must have Future return type: " + Debug.getSimpleName(m));
		}
		short id = export.value();
		String name = Debug.getSimpleName(m);
		logger.debug("  function " + id + " to " + name);
		accept(id, name, args -> {
			try {
				Class<?>[] types = m.getParameterTypes();
				if(args.length != types.length) {
					throw new IllegalArgumentException("invalid parameter length: " + args.length + " != " + types.length);
				}
				Object[] params = new Object[types.length];
				for(int i = 0; i < params.length; i++) {
					params[i] = TypeConversion.toMethodCall(args[i], types[i]);
				}
				@SuppressWarnings("unchecked")
				CompletableFuture<Object> result = (CompletableFuture<Object>) m.invoke(Service.this, params);
				return result;
			} catch(Throwable ex) {
				logger.error("cannot invoke service: " + Debug.getSimpleName(m) + ", with parameter " + Debug.toString(args));
				CompletableFuture<Object> result = new CompletableFuture<Object>();
				result.completeExceptionally(ex);
				return result;
			}
		});
	}

	// ============================================================================================
	// function の定義
	// ============================================================================================
	/**
	 * スーパークラス、スーパーインターフェース、自クラスから @Export 宣言されているメソッドを抽出し呼び出し用に
	 * 定義する。
	 */
	private void declare(Class<?> c){
		Stream.of(c.getMethods())
			.filter( m -> m.getAnnotation(Export.class) != null )
			.forEach( m -> declare(m.getAnnotation(Export.class), m) );
		if(c.getSuperclass() != null){
			declare(c.getSuperclass());
		}
		Stream.of(c.getInterfaces()).forEach(this::declare);
	}

	// ==============================================================================================
	// 処理の実行
	// ==============================================================================================
	/**
	 * 指定された Open メッセージ対する非同期処理を実行します。
	 * @param pipe パイプ
	 * @param open 受信した Open メッセージ
	 */
	void dispatch(Pipe pipe, Open open, String id){
		Function func = functions.get(pipe.function);
		if(func != null) {
			pipe.future.whenComplete(func::disconnect);
			logger.debug(id + ": calling local method: " + func.name);
			func.apply(open.params).whenComplete((result, ex) -> {
				logger.trace(id + ": whenComplete(" + Debug.toString(result) + "," + ex + ")");
				if(ex == null) {
					pipe.close(result);
				} else {
					logger.error(id + ": unexpected exception: " + ex, ex);
					pipe.close(ex, "unexpected error");
				}
			});
		} else {
			logger.debug(id + ": function unbound on: " + pipe.function + ", " + pipe + ", " + open);
			pipe.close(new Exception("function unbound on: " + pipe.function), "function not found: " + pipe.function);
		}
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
	protected Function accept(int function, String name, java.util.function.Function<Object[],CompletableFuture<Object>> f){
		if((short)function != function){
			throw new IllegalStateException("function id out of range for Short: " + function);
		}
		if(functions.containsKey((short)function)){
			throw new IllegalArgumentException("function " + function + " already defined");
		}
		Function func = new Function(name, f);
		functions.put((short)function, func);
		return func;
	}

	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	// Function
	// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
	/**
	 * サービスのファンクション番号に関連づけられている処理の実体。
	 */
	public class Function {
		private final String name;
		private final java.util.function.Function<Object[],CompletableFuture<Object>> onAccept;
		private Consumer<Object> onDisconnect = o -> {};
		/**
		 * @param onAccept ファンクションが呼び出されたときに実行する処理
		 */
		Function(String name, java.util.function.Function<Object[],CompletableFuture<Object>> onAccept) {
			this.name = name;
			this.onAccept = onAccept;
		}

		/**
		 * ピアによって切断されたときの処理を指定します。
		 */
		public Function disconnect(Consumer<Object> f){
			this.onDisconnect = f;
			return this;
		}

		CompletableFuture<Object> apply(Object[] params){
			return onAccept.apply(params);
		}
		void disconnect(Object result, Throwable ex){
			onDisconnect.accept(result);
		}
	}

	/**
	 * ByteBuffer に関する拡張
	 * @param buffer ByteBuffer
	 */
	public static String toString(ByteBuffer buffer, Charset charset){
		if(buffer.isDirect()){
			byte[] b = new byte[buffer.remaining()];
			buffer.get(b);
			return new String(b, charset);
		} else {
			String str = new String(buffer.array(), buffer.arrayOffset(), buffer.remaining());
			buffer.position(buffer.position() + buffer.remaining());
			return str;
		}
	}

}
