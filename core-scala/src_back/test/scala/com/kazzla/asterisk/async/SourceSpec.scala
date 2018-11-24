/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.async

import org.specs2.Specification
import scala.concurrent.{Promise, Await, Future}
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext.Implicits.global

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// SourceSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
class SourceSpec extends Specification { def is = s2"""
Source should:
filter-map-reduce combination. $e1
filterNot, foreach, collect, mkString, find, exists, fork, groupBy, max, count. ${e2 and e3 and e4 and e5 and e6 and e7}
execute parallel. $e8
error if aggregation operation is not defined. $e10
"""

  def e1 = {
    val result = aggregate(0, 1, 2, 3, 4, 5){ _.filter(_%2==0).map(_*2).reduce(_+_) }
    result === (0 + 2 + 4) * 2
  }

  def e2 = {
    aggregate(0, 1, 2,3,4,5){ _.filterNot(_%2==0).reduce(_+_) } === (1+3+5)
  }

  def e3 = {
    var sum = 0
    (aggregate(0, 1, 2, 3, 4, 5){ _.filter(_%2==0).foreach{ sum += _ } } === ()) and (sum === (0+2+4))
  }

  def e4 = {
    aggregate(0, 1, 2, 3, 4, 5){ _.collect{
      case i if i%2==0 => s"*$i*"
    }.mkString("[",",","]") } === "[*0*,*2*,*4*]"
  }

  def e5 = {
    (aggregate(0, 1, 2, 3, 4, 5){ _.find{ _%3==2 }}.get === 2) and
      (aggregate(0, 1, 2, 3, 4, 5){ _.find{ _ < 0 }}.isEmpty must beTrue) and
      (aggregate(0, 1, 2, 3, 4, 5){ _.exists{ _%3==2 }} must beTrue) and
      (aggregate(0, 1, 2, 3, 4, 5){ _.exists{ _ < 0 }}.isEmpty must beFalse)
  }

  def e6 = {
    val (map, max, minus) = aggregate(0, 1, 2, 3, 4, 5){
      _.fork{ (s1, s2, s3) =>
        s1.groupBy{ _ % 2 } zip s2.max zip s3.minBy{ - _ } map { case ((a,b),c) => (a,b,c)}
      }
    }
    (map === Map(0 -> List(0,2,4), 1 -> List(1,3,5))) and (max === 5) and (minus === 5)
  }

  def e7 = {
    aggregate(0, 1, 2, 3, 4, 5){ _.count(_ => true) } === 6
  }

  def e8 = {
    aggregate(0, 1, 2, 3, 4, 5){ _.par.filter{ a =>
      Thread.sleep(util.Random.nextInt(1000))
      true
    }.sum } === (0+1+2+3+4+5)
  }

  def e10 = {
    import scala.language.reflectiveCalls
    val p = Promise[Unit]()
    val a = new Source[Int]{
      def exec() = scala.concurrent.future{
        try {
          Seq(1,2,3).foreach(sequence)
          finish()
          p.failure(new Exception("success!?"))
        } catch {
          case ex:UnsupportedOperationException => p.success(())
          case ex:Throwable => p.failure(ex)
        }
      }
    }
    a.map{ _ * 2 }
    a.exec()
    Await.result(p.future, Duration(10, TimeUnit.SECONDS)) === ()
  }

  def aggregate[T,U](elem:T*)(f:Source[T]=>Future[U]):U = {
    val future = Source(elem){ src => f(src) }
    Await.result(future, Duration(10, TimeUnit.SECONDS))
  }
}
