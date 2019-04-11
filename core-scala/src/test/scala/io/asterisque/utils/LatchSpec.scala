/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.utils

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import org.specs2.Specification

/**
  * @author Takami Torao
  */
class LatchSpec extends Specification {
  def is =
    s2"""
LooseBarrier should:
lock or unlock parallel execution. $e0
"""

  def e0 = {
    val counter = new AtomicInteger(0)

    def wait(n:Int) = {
      val t0 = System.currentTimeMillis()
      while(counter.get() != n) {
        if(System.currentTimeMillis() - t0 > 5 * 1000) {
          throw new IllegalStateException(s"waiting too long: ${counter.get} != $n")
        }
      }
    }

    val threads = Executors.newCachedThreadPool()
    val latch = new Latch()
    val exec = new Runnable() {
      def run = {
        latch.exec(new Runnable() {
          def run = counter.incrementAndGet()
        })
      }
    }

    val result = {
      // 2 つの処理を起動
      threads.execute(exec)
      threads.execute(exec)
      wait(2)
      (latch.getPendings === 0) and (counter.get() === 2)
    } and {
      // ロックをかけて 3 つの処理を起動
      latch.lock()
      threads.execute(exec)
      threads.execute(exec)
      threads.execute(exec)
      Thread.sleep(200)
      (latch.getPendings === 3) and (counter.get() === 2)
    } and {
      // もう一つロックをかけても状況は変わらない
      latch.lock()
      Thread.sleep(200)
      (latch.getPendings === 3) and (counter.get() === 2)
    } and {
      // 複数回ロックがかけられても一回の解除で再開されること
      latch.open()
      Thread.sleep(200)
      (latch.getPendings === 0) and (counter.get() === 5)
    } and {
      // ロックの解除が複数回行われても例外が発生しない
      latch.open()
      wait(5)
      (latch.getPendings === 0) and (counter.get() === 5)
    }
    // 終了
    threads.shutdownNow()
    result
  }
}
