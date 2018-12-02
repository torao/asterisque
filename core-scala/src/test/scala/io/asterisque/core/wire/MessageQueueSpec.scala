package io.asterisque.core.wire

import java.util.Random
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import io.asterisque.core.msg.{Control, Open}
import org.slf4j.LoggerFactory
import org.specs2.Specification

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class MessageQueueSpec extends Specification {
  def is =
    s2"""
constructor parameters variation. $constructors
add and remove listener. $addAndRemoveListener
message offering and polling callback. $offeringAndPollingCallback
polling wait until available message reach. $pollingWithWait
fail offer and alwayas returns null on closed queue. $failOfferAndAlwaysReturnNullOnClosedQueue
The offer() for EOM means close. $offerWithEOMMeansClose
Use as Iterator. $useAsIterator
"""

  private val logger = LoggerFactory.getLogger(getClass)

  private[this] def constructors = {
    val random = new Random(4983)
    val cooperativeLimit = math.abs(random.nextInt())
    (new MessageQueue("constructor", 100).size() === 0) and
      (new MessageQueue("constructor", cooperativeLimit).cooperativeLimit() === cooperativeLimit) and
      (new MessageQueue(null, 1) must throwA[IllegalArgumentException]) and
      (new MessageQueue("constructor", 0) must throwA[IllegalArgumentException])
  }

  private[this] def addAndRemoveListener = {
    val queue = new MessageQueue("poll", 1)
    val listener = new MessageQueue.Listener {}
    queue.addListener(listener)
    val expected = new Open(0, 1, 2, Array[Object]())
    queue.offer(expected)
    val actual = queue.poll()
    queue.removeListener(listener)
    actual === expected
  }

  private[this] def offeringAndPollingCallback = {
    val queue = new MessageQueue("poll", 1)
    val pollables = mutable.Buffer[(Boolean, Int)]()
    val offerables = mutable.Buffer[(Boolean, Int)]()
    queue.addListener(new MessageQueue.Listener {
      override def messagePollable(messageQueue:MessageQueue, pollable:Boolean):Unit = {
        logger.info(s"callback: pollable = $pollable, size = ${messageQueue.size()}")
        pollables.append((pollable, messageQueue.size()))
      }

      override def messageOfferable(messageQueue:MessageQueue, offerable:Boolean):Unit = {
        logger.info(s"callback: offerable = $offerable, size = ${messageQueue.size()}")
        offerables.append((offerable, messageQueue.size()))
      }
    })
    val expected = Seq(
      new Open(0, 10, 100, Array[Object]("foo", Integer.valueOf(1000))),
      new Open(1, 20, 200, Array[Object]("foo", Integer.valueOf(2000))),
      new Open(2, 30, 300, Array[Object]("foo", Integer.valueOf(3000)))
    )
    expected.foreach(queue.offer)
    val actual = expected.map(_ => queue.poll(0, TimeUnit.SECONDS))

    queue.close()
    val eom = queue.poll()

    (pollables.size === 3) and
      (pollables.head._1 must beTrue) and (pollables.head._2 === 1) and
      (pollables(1)._1 must beFalse) and (pollables(1)._2 === 0) and
      (pollables(2)._1 must beFalse) and (pollables(2)._2 === 0) and
      (offerables.size === 4) and
      (offerables.head._1 must beFalse) and (offerables.head._2 === 1) and
      (offerables(1)._1 must beFalse) and (offerables(1)._2 === 2) and
      (offerables(2)._1 must beFalse) and (offerables(2)._2 === 3) and
      (offerables(3)._1 must beTrue) and (offerables(3)._2 === 0) and
      actual.zip(expected).map { case (a, e) => a === e }.reduceLeft(_ and _) and
      (eom must beNull) and
      (queue.offer(expected.head) must throwA[IllegalStateException])
  }

  private[this] def pollingWithWait = {
    val queue = new MessageQueue("poll", 1)
    val expected = new Open(0, 0, 0, Array[Object]())
    val signal = new Object()
    val future = Future {
      signal.synchronized(signal.notifyAll())
      queue.poll(30, TimeUnit.SECONDS)
    }
    signal.synchronized {
      signal.wait()
      Thread.sleep(1000)
      queue.offer(expected)
    }
    Await.result(future, Duration.Inf) === expected
  }

  private[this] def failOfferAndAlwaysReturnNullOnClosedQueue = {
    val queue = new MessageQueue("poll", 1)
    val before = queue.closed()
    queue.close()
    (before must beFalse) and (queue.closed() must beTrue) and
      (queue.poll() must beNull) and (queue.poll(Long.MaxValue, TimeUnit.SECONDS) must beNull) and
      (queue.offer(new Open(0, 0, 0, Array[Object]())) must throwA[IllegalStateException])
  }

  private[this] def offerWithEOMMeansClose = {
    val queue = new MessageQueue("poll", 1)
    val before = queue.closed()
    queue.offer(Control.EOM)
    (before must beFalse) and (queue.closed() must beTrue) and
      (queue.poll() must beNull) and (queue.poll(Long.MaxValue, TimeUnit.SECONDS) must beNull) and
      (queue.offer(new Open(0, 0, 0, Array[Object]())) must throwA[IllegalStateException])
  }

  private[this] def useAsIterator = {
    val queue = new MessageQueue("streaming", Short.MaxValue)
    val last = Future {
      val start = System.currentTimeMillis()
      val i = new AtomicInteger(0)
      Iterator
        .continually(i.getAndIncrement())
        .takeWhile(_ => System.currentTimeMillis() - start < 3 * 1000L)
        .foreach(i => {
          queue.offer(new Open(0, 0, 0, Array[Object](Integer.valueOf(i))))
          Thread.sleep(100)
        })
      queue.close()
      logger.info(f"iterator: ${i.get()}%d message offered")
      i.get() - 1
    }
    val results = queue.iterator().asScala.map(o => o.asInstanceOf[Open].params(0).asInstanceOf[Integer].intValue).toList
    println(results)
    results
      .zipWithIndex
      .map { case (actual, expected) => actual === expected }
      .reduceLeft(_ and _)
  }
}
