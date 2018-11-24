/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque.util

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import org.specs2.Specification

import scala.concurrent.Promise

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// LooseBarrier
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class LatchSpec extends Specification { def is = s2"""
LooseBarrier should:
lock or unlock parallel execution. $e0
"""
  def e0 = {
    val counter = new AtomicInteger(0)
    def wait(n:Int) = while(counter.get() != n){ }
    val threads = Executors.newCachedThreadPool()
    val latch = new Latch()
    val exec = new Runnable(){
      def run = {
        latch.exec(new Runnable(){
          def run = counter.incrementAndGet()
        })
      }
    }
    // 2 つの処理を起動
    threads.execute(exec)
    threads.execute(exec)
    wait(2)
    // ロックをかけて 3 つの処理を起動
    latch.lock(true)
    threads.execute(exec)
    threads.execute(exec)
    threads.execute(exec)
    Thread.sleep(200)
    val c0 = counter.get()
    // もう一つロックをかける
    latch.lock(true)
    Thread.sleep(200)
    val c1 = counter.get()
    // 一つロックを解除してロックが外れないこと
    latch.lock(false)
    Thread.sleep(200)
    val c2 = counter.get()
    // 最後のロックを解除して全て実行される
    latch.lock(false)
    wait(5)
    // 終了
    threads.shutdownNow()
    (c0 === 2) and (c1 === 2) and (c2 === 2)
  }
}
