package io.asterisque;

import javax.annotation.Nonnull;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;

/**
 * プロトコルで使用する定数や共通機能を実装するクラス。
 *
 * @author Takami Torao
 */
public final class Asterisque {

  /**
   * コンストラクタはクラス内に隠蔽されています。
   */
  private Asterisque() {
  }

  /**
   * ディレクトリ名や URI の一部に使用できる asterisque の識別子です。
   */
  public static final String ID = "asterisque";

  /**
   * UTF-8 を表す文字セットです。
   */
  public static final Charset UTF8 = StandardCharsets.UTF_8;

  /**
   * 長さ 0 のバイト配列。
   */
  public interface Empty {
    byte[] Bytes = new byte[0];
    char[] Chars = new char[0];
  }

  public static class Future {
    private Future() {
    }

    public static <T> CompletableFuture<T> fail(@Nonnull Throwable ex) {
      CompletableFuture<T> future = new CompletableFuture<>();
      future.completeExceptionally(ex);
      return future;
    }
  }

  // ==============================================================================================
  // スレッドグループ
  // ==============================================================================================
  /**
   * asterisque が使用するスレッドの所属するグループです。
   */
  public static final ThreadGroup threadGroup = new ThreadGroup(ID);

  // ==============================================================================================
  // スレッドファクトリの参照
  // ==============================================================================================

  /**
   * 指定されたロールのためのスレッドファクトリを参照します。
   */
  public static Thread newThread(String role, Runnable r) {
    return new Thread(threadGroup, r, ID + "." + role);
  }

  // ==============================================================================================
  // スレッドファクトリの参照
  // ==============================================================================================

  /**
   * 指定されたロールのためのスレッドファクトリを参照します。
   */
  public static ThreadFactory newThreadFactory(String role) {
    return r -> newThread(role, r);
  }

  /**
   * 全てのビットが 0 の UUID です。
   */
  public static final UUID Zero = new UUID(0, 0);

  public static final class Protocol {
    private Protocol() {
    }

    /**
     * プロトコル識別子兼エンディアン確認用の 2 バイトのマジックナンバー。ASCII コードで "*Q" の順でバイナリスト
     * リームの先頭に出現しなければならない。
     */
    public static final short Signature = 0x2A51;

    /**
     * プロトコルバージョン 0.1 を表す 2 バイト整数。
     */
    public static final short Version_0_1 = 0x0100;

    /**
     * 現在の実装がサポートしているバージョン。
     */
    public static final short CurrentVersion = Version_0_1;
  }

  public static String logPrefix() {
    return "-:--------";
  }

  public static String logPrefix(boolean isServer) {
    return logPrefix(isServer, null);
  }

  public static String logPrefix(boolean isServer, UUID id) {
    return (isServer ? 'S' : 'C') + ":" + (id == null ? "--------" : id.toString().substring(0, 8).toUpperCase());
  }
}
