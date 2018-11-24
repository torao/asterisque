package io.asterisque;

import io.asterisque.core.Debug;
import io.asterisque.core.codec.VariableCodec;
import io.asterisque.core.msg.Open;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * サブクラスの実装により通信相手の {@link io.asterisque.Node} に対して提供するサービスを定義します。
 * <p>
 * サービスの function は非同期処理を行うメソッドとして実装し {@link CompletableFuture} 型を返す必要があります。
 * function 番号を {@link io.asterisque.Export} に指定することで function として認識されます。
 * <pre>
 * // インターフェース定義
 * public interface Reverse {
 *   \@Export(10)
 *   public CompletableFuture&lt;String&gt; reverse(String text);
 * }
 *
 * // サーバ側
 * public class ReverseService extends Service with Reverse {
 *   public CompletableFuture&lt;String&gt; reverse(String text) {
 *     return CompletableFuture.applyAsync(() -> new String(text.toCharArray().reverse()));
 *   }
 * }
 * Node node = Node("reverse").serve(new ReverseService()).build();
 *
 * // クライアント側
 * Reserve remote = session.bind(Reverse.class);
 * remote.reverse("hello, world").thenAccept(System.out::println);  // dlrow ,olleh
 * </pre>
 * もう一つの方法として function 番号を指定して処理を実装する方法があります。この方法によって動的な定義に
 * 対応することが出来ますが型安全ではありません。
 * <pre>
 * // サーバ側
 * public class MyAsyncService extends Service {
 *   10 accept { args => Future("input: " + args.head) }
 *   20 accept { args => ... }
 * }
 * // クライアント側
 * session.open(10, "ABS").onSuccess{ result =>
 * System.out.println(result)   // input: ABC
 * }.onFailure{ ex =>
 * ex.printStackTrace()
 * }
 * </pre>
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
  private final ConcurrentHashMap<Short, Func> functions = new ConcurrentHashMap<>();

  /**
   * このサブクラスに定義されている全ての {@link Export} 宣言されているメソッドを function として登録します。
   */
  protected Service() {
    logger.debug("binding " + (getClass().isAnonymousClass() ? "<anonymous>" : getClass().getSimpleName()) + " as service");

    // このインスタンスに対するファンクションの定義
    declare(getClass());
  }

  /**
   * 現在の function 処理を実行しているパイプを参照します。実行スレッドが function の呼び出し処理でない場合は
   * 直ちに fail した `Future` を返しラムダは実行されません。
   * このメソッドは利用可能なパイプを引数のラムダに渡し、ラムダが返す `Future` を返値とします。
   * 相手の呼び出しのためにセッションが必要な場合は [[Pipe.session]] で参照することが出来ます。
   */
  protected <T> CompletableFuture<T> withPipe(java.util.function.Function<Pipe, CompletableFuture<T>> f) {
    return Pipe.orElse(() -> {
      CompletableFuture<T> future = new CompletableFuture<>();
      future.completeExceptionally(new Exception("pipe can only refer in caller thread of function"));
      return future;
    }, f);
  }

  /**
   * 指定されたメソッドを function として登録します。
   *
   * @param export function 番号
   * @param m      function として登録するメソッド
   */
  private void declare(@Nonnull Export export, @Nonnull Method m) {
    short id = export.value();
    String name = Debug.getSimpleName(m);
    if (m.getReturnType() != CompletableFuture.class) {
      throw new IllegalArgumentException(
          "method with @Export annotation must have CompletableFuture return type: " + name);
    }
    logger.debug("  function " + id + " to " + name);
    accept(id, name, (args, codec) -> {
      try {
        Class<?>[] types = m.getParameterTypes();
        if (args.length != types.length) {
          throw new IllegalArgumentException("invalid parameter length: " + args.length + " != " + types.length);
        }
        Object[] params = new Object[types.length];
        for (int i = 0; i < params.length; i++) {
          params[i] = codec.transferableToNative(args[i], types[i]);
        }
        @SuppressWarnings("unchecked")
        CompletableFuture<Object> result = (CompletableFuture<Object>) m.invoke(Service.this, params);
        return result;
      } catch (Throwable ex) {
        logger.error("cannot invoke service: " + Debug.getSimpleName(m) + ", with parameter " + Debug.toString(args));
        CompletableFuture<Object> result = new CompletableFuture<Object>();
        result.completeExceptionally(ex);
        return result;
      }
    });
  }

  /**
   * 指定されたクラス自身やそのスーパークラス、スーパーインターフェースから {@link Export} 宣言されているメソッドを抽出し
   * function として呼び出しする準備を行います。
   *
   * @param c function を抽出するクラス
   */
  private void declare(@Nonnull Class<?> c) {
    Stream.of(c.getMethods())
        .filter(m -> m.getAnnotation(Export.class) != null)
        .forEach(m -> declare(m.getAnnotation(Export.class), m));
    if (c.getSuperclass() != null) {
      declare(c.getSuperclass());
    }
    Stream.of(c.getInterfaces()).forEach(this::declare);
  }

  // ==============================================================================================
  // 処理の実行
  // ==============================================================================================

  /**
   * 指定された Open メッセージ対する非同期処理を実行します。
   *
   * @param pipe パイプ
   * @param open 受信した Open メッセージ
   */
  void dispatch(Pipe pipe, Open open, String id, VariableCodec codec) {
    Func func = functions.get(pipe.function);
    if (func != null) {
      pipe.future.whenComplete(func::disconnect);
      logger.debug(id + ": calling local method: " + func.name);
      func.apply(open.params, codec).whenComplete((result, ex) -> {
        logger.trace(id + ": whenComplete(" + Debug.toString(result) + "," + ex + ")");
        if (ex == null) {
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

  /**
   * このサービスに対して function 番号での呼び出しが行われたときに起動する処理を指定します。
   *
   * @param function function 番号 (0-65535)
   * @param name     function を人が識別するための名前
   * @param f        呼び出し時に起動する処理
   * @return 登録された function 定義
   */
  protected Func accept(int function, @Nonnull String name, @Nonnull BiFunction<Object[], VariableCodec, CompletableFuture<Object>> f) {
    if ((short) function != function) {
      throw new IllegalArgumentException("function id out of range for UINT16: " + function);
    }
    return functions.compute((short) function, (x, impl) -> {
      if (impl != null) {
        throw new IllegalArgumentException("function " + function + " already defined");
      }
      return new Func(name, f);
    });
  }

  protected void reset(int function) {
    functions.remove((short) function);
  }

  /**
   * サービスのファンクション番号に関連づけられている処理の実体を表すクラス。
   */
  public class Func {

    /**
     * ファンクション名。
     */
    private final String name;

    /**
     * 処理の実装。
     */
    private final BiFunction<Object[], VariableCodec, CompletableFuture<Object>> onAccept;

    private Consumer<Object> onDisconnect = o -> {
    };

    /**
     * @param onAccept ファンクションが呼び出されたときに実行する処理
     */
    private Func(String name, BiFunction<Object[], VariableCodec, CompletableFuture<Object>> onAccept) {
      this.name = name;
      this.onAccept = onAccept;
    }

    /**
     * ピアによって切断されたときの処理を指定します。
     */
    public Func disconnect(Consumer<Object> f) {
      this.onDisconnect = f;
      return this;
    }

    CompletableFuture<Object> apply(Object[] params, VariableCodec codec) {
      return onAccept.apply(params, codec);
    }

    void disconnect(Object result, Throwable ex) {
      onDisconnect.accept(result);
    }
  }

  /**
   * ByteBuffer に関する拡張
   *
   * @param buffer ByteBuffer
   */
  public static String toString(ByteBuffer buffer, Charset charset) {
    if (buffer.isDirect()) {
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
