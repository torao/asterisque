package io.asterisque.wire.rpc

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import io.asterisque.wire.ProtocolException
import io.asterisque.wire.message.Message.{Close, Open}
import io.asterisque.wire.rpc.PipeSpace._
import javax.annotation.Nonnull
import org.slf4j.LoggerFactory

import scala.annotation.tailrec

/**
  * Pipe の生成と消滅を管理するクラス。セッションからパイプの管理に関する部分を切り離した。
  */
private[rpc] class PipeSpace(session:Session) {

  /**
    * 新規のパイプ ID を発行するためのシーケンス番号。
    */
  private[this] val sequence = new AtomicInteger(0)

  /**
    * 現在アクティブな Pipe のマップ。
    */
  private[this] val pipes = new ConcurrentHashMap[Short, Pipe]()

  /**
    * クローズされているかの判定。
    */
  private[this] val closed = new AtomicBoolean(false)

  /**
    * このパイプスペースで新しいパイプを作成するときに使用するビットフラグ。
    */
  private[this] val pipeMask = if(session.isPrimary) Pipe.UniqueMask else 0

  /**
    * このパイプ空間から指定された ID のパイプを参照します。ID に該当するパイプが存在しない場合は None を返します。
    *
    * @param pipeId 参照するパイプの ID
    * @return パイプ
    */
  @Nonnull
  def get(pipeId:Short):Option[Pipe] = Option(pipes.get(pipeId))

  /**
    * ピアから受信した Open メッセージに対応するパイプを構築します。
    *
    * @param open Open メッセージ
    * @throws ProtocolException 指定されたパイプ ID がすでに利用されている場合
    */
  @Nonnull
  @throws[ProtocolException]
  def create(@Nonnull open:Open):Pipe = {
    if(!closed.get) {
      throw new IllegalStateException("session has already been closed")
    }

    // 要求されたパイプ ID がプロトコルに従っていることを確認
    val hasPrimaryMask = (open.pipeId & Pipe.UniqueMask) != 0
    if(session.isPrimary == hasPrimaryMask) {
      val msg = if(session.isPrimary) {
        f"pipe-id with primary mask ${open.pipeId & 0xFFFF}%04X isn't accepted from non-primary peer"
      } else {
        f"pipe-id without primary mask ${open.pipeId & 0xFFFF}%04X isn't acceptable from primary peer"
      }
      throw new ProtocolException(msg)
    }

    // 新しいパイプを構築して登録
    val pipe = new Pipe(open, session.stub)
    val old = pipes.putIfAbsent(open.pipeId, pipe)
    if(old != null) {
      // 既に使用されているパイプ ID が指定された場合はエラー
      val msg = s"duplicate pipe-id specified: ${open.pipeId}; $old"
      logger.error(msg)
      throw new ProtocolException(msg)
    }
    pipe
  }

  /**
    * ピアに対して Open メッセージを送信するためのパイプを生成します。
    *
    * @param priority プライオリティ
    * @param function ファンクション ID
    */
  @Nonnull
  def create(priority:Byte, function:Short):Pipe = {
    @tailrec
    def _create():Pipe = {
      if(!closed.get) {
        throw new IllegalStateException("session has already been closed")
      }
      val id = ((sequence.getAndIncrement & 0x7FFF) | pipeMask).toShort
      val open = new Open(id, priority, function, Array.empty)
      val pipe = new Pipe(open, session.stub)
      if(pipes.putIfAbsent(id, pipe) == null) pipe else _create()
    }

    _create()
  }

  /**
    * 指定されたパイプ ID のパイプを削除します。
    *
    * @param pipeId パイプ空間から削除するパイプの ID
    */
  def destroy(pipeId:Short):Unit = {
    val old = pipes.remove(pipeId)
    if(old == null) {
      logger.debug(f"pipe is not exist: ${pipeId & 0xFF}%04X")
    }
  }

  /**
    * このパイプ空間が保持している全てのパイプを破棄します。graceful に true を指定した場合はパイプに対して Close(Abort)
    * メッセージを送信します。
    *
    * @param graceful Close メッセージ配信を行わず強制的に終了する場合 false
    */
  def close(graceful:Boolean):Unit = if(closed.compareAndSet(false, true)) {
    if(graceful) {
      // 残っているすべてのパイプに Close メッセージを送信
      pipes.values.forEach(_.closeWithError(Close.Code.SESSION_CLOSING, s"session ${session.id} is closing"))
    }
    pipes.clear()
  }
}

object PipeSpace {
  private[PipeSpace] val logger = LoggerFactory.getLogger(classOf[PipeSpace])
}
