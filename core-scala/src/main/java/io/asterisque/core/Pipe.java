package io.asterisque.core;

import io.asterisque.core.msg.Block;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * function に対する呼び出し状態を表すクラスです。function の呼び出し開始から終了までのスコープを持ち、その呼び
 * 出し結果は `pipe.future` を使用して非同期で参照することができます。またパイプは非同期メッセージングにおける
 * メッセージの送出先/流入元を表しており同期ストリーミングもパイプを経由して行います。
 * <p>
 * パイプの ID は `Session` を開始した側 (クライアント側) が最上位ビット 0、相手からの要求を受け取った側 (サー
 * ビス側) が 1 を持ちます。このルールは通信の双方で相手側との合意手続きなしに重複しないユニークなパイプ ID を
 * 発行することを目的としています。このため一つのセッションで同時に行う事が出来る呼び出しは最大で 32,768、パイプを
 * 共有しているピアの双方で 65,536 個までとなります。
 */
public interface Pipe {

  /**
   * {@link Session#isPrimary} が true の通信端点側で新しいパイプ ID を発行するときに立てるビットフラグです。
   */
  short UniqueMask = (short) (1 << 15);

  short id();

  short function();

  byte priority();

//  @Nonnull
//  Session session();

  /**
   * このパイプによる双方の処理で確定した結果 (成功/失敗にかかわらず) を参照するための Future です。
   * パイプの結果が確定した時点でパイプはクローズされています。
   */
  @Nonnull
  CompletableFuture<Object> future();

  /**
   * 非同期メッセージングやストリーミングのための {@link Block} メッセージ受信を行う {@code Stream}。返値のストリームは
   * スレッドセーフではありません。
   */
  @Nonnull
  Stream<Block> stream();

  /**
   * このパイプに対する非同期メッセージングの送信を行うメッセージシンクを参照します。
   * クローズされていないパイプに対してであればどのようなスレッドからでもメッセージの送信を行うことが出来ます。
   */
  @Nonnull
//  PipeMessageSink sink();

  /**
   * このパイプがクローズされているときに true を返します。
   *
   * @return パイプがクローズされているとき true
   */
  boolean closed();

  /**
   * 指定された {@link OutputStream} を使用してストリーム形式のブロック出力を行います。
   * 出力データが内部的なバッファの上限に達するか {@code flush()} が実行されるとバッファの内容が {@link Block}
   * として非同期で送信されます。
   * ストリームはマルチスレッドの出力に対応していません。
   */
  @Nonnull
  OutputStream out();

  /**
   * このパイプがメッセージとして受信したバイナリデータを `InputStream` として参照します。
   * 非同期で到着するメッセージを同期ストリームで参照するために、入力ストリームを使用する前に [[useInputStream()]]
   * を呼び出して内部的なキューを準備しておく必要があります。[[useInputStream()]] を行わず入力ストリームを参照
   * した場合は例外となります。
   */
  @Nonnull
  InputStream in() throws IOException;

}
