/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import scala.concurrent._
import scala.concurrent.duration._
import org.specs2.Specification
import org.specs2.execute.Result
import java.io.{File, IOException}
import org.slf4j.LoggerFactory
import java.text.DateFormat
import java.security.cert.X509Certificate

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// WireSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
*/
abstract class WireSpec extends Specification { def is = s2"""
Wire should:
have either server or not flag. $e0
have correct lock status. $e1
have correct active/deactive status. $e2
callback on receive. $e25
transfer messages duplex. $e3
append and remove onReceive handlers. $e4
buffers all received messages before bind. $e5
results IOException from future of send() if wire wsClosed. $e6
not dispatch any message from receive() if wire wsClosed. $e7
be able to refer TLS/SSL session. $e8
have correct peer name. $e9
"""

  /**
   * subclass should pair of transmission endpoint as tuple of wires.
  */
  def wires(f:(Wire,Wire)=>Result):Result

  def secureWire(f:(Wire)=>Result):Result = skipped

  def e0 = wires{ (w1, w2) =>
    w1.isServer !=== w2.isServer
  }

  def e1 = wires{ (w1, w2) =>
    (w1.isClosed must beFalse) and (w2.isClosed must beFalse) and
    { w1.close(); w1.isClosed must beTrue } and { w2.close(); w2.isClosed must beTrue }
  }

  def e2 = wires{ (w1, w2) =>
    (w1.isActive must beFalse) and (w2.isActive must beFalse) and
    { w1.start(); w1.isActive must beTrue  } and { w2.start(); w2.isActive must beTrue  } and
    { w1.stop();  w1.isActive must beFalse } and { w2.stop();  w2.isActive must beFalse }
  }

  def e25 = wires{ (w1, w2) =>
    LoggerFactory.getLogger(classOf[WireSpec]).info("------------------")
    val m = Open(0, 0, Seq())
    val p = Promise[Message]()
    w2.onReceive ++ { m => p.success(m) }
    w1.start()
    w2.start()
    w1.send(m)
    Await.result(p.future, Limit) === m
  }

  def e3 = wires{ (w1, w2) =>
    val m1 = Open(1, 2, Seq[Any]())
    val m2 = Close(2, Right("success"))
    val p1 = Promise[Message]()
    val p2 = Promise[Message]()
    w1.onReceive ++ { m => p1.success(m) }
    w2.onReceive ++ { m => p2.success(m) }
    w1.start()
    w2.start()
    w1.send(m1)
    val r2 = Await.result(p2.future, Limit)
    w2.send(m2)
    val r1 = Await.result(p1.future, Limit)
    (r2 === m1) and (r1 === m2)
  }

  def e4 = wires{ (w1, w2) =>
    var count = 0
    case class H(n:Int){
      private val p = Promise[Message]()
      def h(m:Message):Unit = { count += n; p.success(m) }
      def r:Message = Await.result(p.future, Limit)
    }

    count = 0
    val hs1 = List(H(1), H(2), H(3))
    hs1.foreach{ w2.onReceive ++ _.h }
    w2.start()
    w1.send(Open(0, 0, Seq[Any]()))
    hs1.foreach{ _.r }
    count === hs1.map{ _.n }.sum
  }

  def e5 = wires{ (w1, w2) =>
    val msg = Array(Open(5, 0, Seq[Any]()), Block.eof(5), Close(5, Right("hoge")))
    val r = new collection.mutable.ArrayBuffer[Message]()
    val p1 = Promise[Int]()
    val p2 = Promise[Int]()
    def h(m:Message){
      r.append(m)
      if(r.length == 1){
        p1.success(1)
      }
      if(r.length == msg.length){
        p2.success(r.length)
      }
    }

    w2.onReceive ++ h
    assert(! w2.isActive)
    w1.send(msg(0))
    Thread.sleep(500)
    val notReceiveBeforeStart = (r.length === 0) and (p1.future.isCompleted must beFalse)
    w2.start()
    Await.result(p1.future, Limit)
    val receiveAfterStart = (r.length === 1) and (p1.future.isCompleted must beTrue)
    w1.send(msg(1))
    w1.send(msg(2))
    Await.result(p2.future, Limit)
    val receiveAll = r.length === 3
    notReceiveBeforeStart and receiveAfterStart and receiveAll
  }

  def e6 = wires{ (w1, w2) =>
    w1.send(Open(6, 0, Seq[Any]()))
    w1.close()
    w1.send(Open(6, 1, Seq[Any]())) must throwA[java.io.IOException]
  }

  def e7 = wires{ (w1, w2) =>
    var count = 0
    w2.onReceive ++ { _ => count += 1 }
    w2.start()
    w1.send(Open(7, 0, Seq[Any]()))
    Thread.sleep(500)
    w2.close()
    try {
      w1.send(Open(7, 1, Seq[Any]()))
      Thread.sleep(500)
      count === 1
    } catch {
      case ex:IOException => skipped
    }
  }

  def e8 = secureWire { w =>
    val session = Await.result(w.tls, Limit)
    session match {
      case Some(s) =>
        val logger = LoggerFactory.getLogger(getClass)
        logger.info(s"ID: ${s.getId.map{ b => "%02X".format(b & 0xFF) }.mkString}")
        logger.info(s"CipherSuite: ${s.getCipherSuite}")
        logger.info(s"CreationTime: ${DateFormat.getDateTimeInstance.format(s.getCreationTime)}")
        logger.info(s"LastAccessedTime: ${DateFormat.getDateTimeInstance.format(s.getLastAccessedTime)}")
        logger.info(s"Peer: ${s.getPeerHost}:${s.getPeerPort} ${s.getPeerPrincipal}")
        s.getPeerCertificates.foreach{ c =>
          logger.info(s"  ${c.asInstanceOf[X509Certificate].getSubjectDN.getName}")
        }
      case None =>
        throw new Exception("session not started for secure wire")
    }
    success
  }

  def e9 = wires{ (w1, w2) =>
    (w1.peerName !=== null) and (w1.peerName !=== null)
  }

  val Limit = Duration(3, SECONDS)

}

class PipeWireSpec1 extends WireSpec {
  def wires(f:(Wire,Wire)=>Result):Result = {
    val w = Wire.newPipe()
    using(w._1){ w1 =>
      using(w._2){ w2 =>
        f(w1, w2)
      }
    }
  }
}

class PipeWireSpec2 extends PipeWireSpec1 {
  override def wires(f:(Wire,Wire)=>Result):Result = {
    super.wires{ (w1, w2) => f(w2, w1) }
  }
}

class LoadKeyStoreSpec extends Specification{ def is = s2"""
loadKeyStore() should:
load key-store with correct passwords. $e1
throw wsCaughtException if incorrect passowrds. $e2
"""

  def e1 = {
    val cert = Wire.loadSSLContext(new File("ca/client.jks"), "kazzla", "kazzla", new File("ca/cacert.jks"), "kazzla")
    cert !=== null
  }

  def e2 = {
    Wire.loadSSLContext(new File("ca/client.jks"), "", "", new File("ca/cacert.jks"), "kazzla") must throwA[IOException]
  }

}