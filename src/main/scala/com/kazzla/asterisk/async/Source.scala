/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk.async

import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.collection.{GenTraversableOnce, mutable}
import scala.util.{Try, Success}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag
import java.util.concurrent.atomic.{AtomicReference, AtomicInteger}
import scala.annotation.tailrec

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Source
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * 巨大なログファイルの集計や非同期受信データの集計など、非同期で到着する要素集合に対して利用できるコンビネータを
 * 定義するトレイトです。
 * 標準の collection はすでに存在する (あるいは算出可能な) 要素集合から必要に応じてデータを取り出す事の出来る
 * pull 型です。このトレイトは非同期で発生する push 型の要素集合に対する操作を効率的に定義します。
 *
 * map や fold などの処理を定義している間にデータを受信してしまわないよう、単一スレッドのイベントループ内で定義と
 * 受信を行う必要があります。
 *
 * @author Takami Torao
 */
trait Source[A] {

	private[this] var operations = Seq[(Traverse[A])=>Unit]()

	@volatile
	private[this] var _count:Long = 0

	def count = _count

	def map[B](f:(A)=>B):Source[B] = {
		val src = new Joint[B]()
		val g:(Traverse[A])=>Unit = {
			case Sequence(value) =>
				src.sequence(f(value))
			case Finish() => src.finish()
			case Failure(cause) => src.failure(cause)
		}
		operations = operations :+ g
		src
	}

	def filter(f:(A)=>Boolean):Source[A] = {
		val src = new Joint[A]()
		val g:(Traverse[A])=>Unit = {
			case t@Sequence(value) =>
				if(f(value)) { src.traverse(t) }
			case other => src.traverse(other)
		}
		operations = operations :+ g
		src
	}

	def filterNot(f:(A)=>Boolean):Source[A] = { filter({ e => ! f(e) }) }

	def collect[B](pf:PartialFunction[A,B]):Source[B] = {
		val src = new Joint[B]()
		val g:(Traverse[A])=>Unit = {
			case Sequence(value) =>
				if(pf.isDefinedAt(value)){
					src.sequence(pf(value))
				}
			case Finish() => src.finish()
			case Failure(cause) => src.failure(cause)
		}
		operations = operations :+ g
		src
	}

	def foreach(f:(A)=>Unit):Future[Unit] = synchronized {
		val promise = Promise[Unit]()
		val g:(Traverse[A])=>Unit = {
			case Sequence(value) => f(value)
			case Finish() => promise.success(())
			case Failure(cause) => promise.failure(cause)
		}
		operations = operations :+ g
		promise.future
	}

	def reduceLeftOption[B>:A](op:(B,A)=>B):Future[Option[B]] = {
		var result:Option[B] = None
		foreach{ value =>
			if(result.isEmpty){
				result = Some(value)
			} else {
				result = Some(op(result.get, value))
			}
		}.map{ _ => result}
	}

	def reduceLeft[B >: A](f:(B,A)=>B):Future[B] = {
		val promise = Promise[B]()
		reduceLeftOption(f).onComplete{
			case Success(Some(result)) => promise.success(result)
			case Success(None) =>
				promise.failure(new UnsupportedOperationException("empty source"))
			case util.Failure(cause) => promise.failure(cause)
		}
		promise.future
	}

	def reduce[A1 >: A](op: (A1, A1) => A1):Future[A1] = reduceLeft(op)
	def reduceOption[A1 >: A](op: (A1, A1) => A1):Future[Option[A1]] = reduceLeftOption(op)

	def foldLeft[B](z:B)(f:(B,A)=>B):Future[B] = {
		// 試験的に並列実行対応
		val result = new AtomicReference[B](z)
		foreach{ value =>
			@tailrec
			def g(){
				val r0 = result.get()
				val r1 = f(r0, value)
				if(!result.compareAndSet(r0, r1)){
					g()
				}
			}
			g()
		}.map{ _ => result.get }
	}

	def fold[A1>:A](z:A1)(op:(A1,A1)=>A1):Future[A1] = foldLeft(z)(op)

	def find(p:(A)=>Boolean):Future[Option[A]] = {
		val promise = Promise[Option[A]]()
		val g:(Traverse[A])=>Unit = {
			case Sequence(value) =>
				if(! promise.isCompleted && p(value)){
					promise.success(Some(value))
				}
			case Finish() =>
				if(! promise.isCompleted){
					promise.success(None)
				}
			case Failure(cause) => promise.failure(cause)
		}
		operations = operations :+ g
		promise.future
	}

	def exists(p:(A)=>Boolean):Future[Boolean] = find(p).map{ _.isDefined }

	def groupBy[K](f:(A)=>K):Future[Map[K,List[A]]] = foldLeft(mutable.HashMap[K,List[A]]()){ (map, value) =>
		val key = f(value)
		map.get(key) match {
			case Some(list) => map.put(key, list :+ value)
			case None => map.put(key, value :: List())
		}
		map
	}.map{ _.toMap }

	def maxBy[B](f:(A)=>B)(implicit cmp:Ordering[B]):Future[A] = reduceLeft{ (x, y) => if (cmp.gteq(f(x), f(y))) x else y }
	def max[B>:A](implicit cmp:Ordering[B]):Future[A] = reduceLeft{ (x, y) => if (cmp.gteq(x, y)) x else y }
	def minBy[B](f:(A)=>B)(implicit cmp:Ordering[B]):Future[A] = maxBy(f)(cmp.reverse)
	def min[B>:A](implicit cmp:Ordering[B]):Future[A] = max(cmp.reverse)

	def aggregate[B](z:B)(seqop:(B,A)=>B, combop:(B,B)=>B):Future[B] = foldLeft(z)(seqop)
	def sum[B >: A](implicit num: Numeric[B]):Future[B] = foldLeft(num.zero)(num.plus)
	def product[B>:A](implicit num:Numeric[B]):Future[B] = foldLeft(num.one)(num.times)

	def mkString(start:String, sep:String, end:String):Future[String] = {
		foldLeft(new StringBuilder()){ (x, y) =>
			if(x.length != 0){
				x.append(sep)
			}
			x.append(y)
		}.map { start + _.toString() + end }
	}

	def mkString(sep:String):Future[String] = mkString("", sep, "")
	def mkString:Future[String] = mkString("")

	def fork[B1,B2](f:(Source[A],Source[A])=>Future[(B1,B2)]):Future[(B1,B2)] = {
		fork(2){ s => f(s(0), s(1)) }
	}
	def fork[B1,B2,B3](f:(Source[A],Source[A],Source[A])=>Future[(B1,B2,B3)]):Future[(B1,B2,B3)] = {
		fork(3){ s => f(s(0), s(1), s(2)) }
	}
	def fork[B1,B2,B3,B4](f:(Source[A],Source[A],Source[A],Source[A])=>Future[(B1,B2,B3,B4)]):Future[(B1,B2,B3,B4)] = {
		fork(4){ s => f(s(0), s(1), s(2), s(3)) }
	}

	private[this] def fork[B](num:Int)(f:IndexedSeq[Source[A]]=>Future[B]):Future[B] = {
		val next = (0 until num).map{ _ => new Joint[A]() }.toVector
		val future = f(next)
		val g:(Traverse[A])=>Unit = { t =>
			next.foreach{ _.traverse(t) }
		}
		operations = operations :+ g
		future
	}

	def toBuffer[B>:A]:Future[mutable.Buffer[B]] = foldLeft(mutable.Buffer[B]()){ (array, elem) =>
		array.append(elem)
		array
	}
	def toIndexedSeq:Future[IndexedSeq[A]] = toBuffer[A].map{ _.toIndexedSeq }
	def toArray[B>:A](implicit arg0:ClassTag[B]):Future[Array[B]] = toBuffer[A].map{ _.toArray(arg0) }
	def toSeq:Future[Seq[A]] = toBuffer[A].map{ _.toSeq }
	def toList:Future[List[A]] = toBuffer[A].map{ _.toList }
	def toIterable:Future[Iterable[A]] = toBuffer[A].map{ _.toIterable }

	def count(f:(A)=>Boolean):Future[Int] = foldLeft(0){ (total, elem) => total + (if(f(elem)) 1 else 0) }

	@deprecated("experimental")
	def par(implicit ec:ExecutionContext):Source[A] = {
		val src = new ParallelJoint[A]()
		val g:(Traverse[A])=>Unit = { src.traverse }
		operations = operations :+ g
		src
	}

	private[async] def traverse(elem:Traverse[A]):Unit = {
		if(operations.isEmpty){
			throw new UnsupportedOperationException(s"incomplete combinator chain")
		} else {
			operations.foreach{ op =>
				try {
					op(elem)
				} catch {
					case ex:ThreadDeath => throw ex
					case ex:Throwable =>
						Try(op(Failure(ex))).recover{ case e => e.printStackTrace() }
						throw ex
				}
			}
			if(elem.isInstanceOf[Sequence[_]]){
				_count += 1
			} else {
				operations = Seq({ _ => None })
			}
		}
	}
	protected def sequence(value:A):Unit = traverse(Sequence(value))
	protected def finish():Unit = traverse(Finish())
	protected def failure(cause:Throwable):Unit = traverse(Failure(cause))
}

object Source {
	def apply[A,B](t:GenTraversableOnce[A])(op:Source[A]=>Future[B])(implicit ec:ExecutionContext):Future[B] = {
		class X extends Source[A] {
			def start():Unit = concurrent.future {
				t.foreach{ x => sequence(x) }
				finish()
			}
		}
		val s = new X()
		val f = op(s)
		s.start()
		f
	}
}

private[async] class Joint[A] extends Source[A] {
	@volatile
	private[this] var end = false
	private[async] override def traverse(elem:Traverse[A]):Unit = if(!end){
		try {
			super.traverse(elem)
		} catch {
			case ex:Throwable =>
				end = true
				throw ex
		}
		elem match {
			case _:Finish[_] => end = true
			case _:Failure[_] => end = true
			case _ => None
		}
	}
}

private[async] class ParallelJoint[A](implicit ec:ExecutionContext) extends Joint[A] {
	private[this] val progressing = new AtomicInteger(0)
	private[async] override def traverse(elem:Traverse[A]):Unit = {
		ec.execute(new Runnable {
			override def run(): Unit = elem match {
				case _:Finish[A] =>
					// 先行する計算より先に finish が実行されてしまうと途中の状態になってしまうため待機
					progressing.synchronized{
						while(progressing.get() > 0){
							progressing.wait()
						}
						ParallelJoint.super.traverse(elem)
					}
				case _ =>
					progressing.incrementAndGet()
					ParallelJoint.super.traverse(elem)
					if(progressing.decrementAndGet() == 0){
						progressing.synchronized{
							progressing.notify()
						}
					}
			}
		})
	}
}

private[async] sealed abstract class Traverse[A]()
private[async] case class Sequence[A](value:A) extends Traverse[A]
private[async] case class Finish[A]() extends Traverse[A]
private[async] case class Failure[A](cause:Throwable) extends Traverse[A]
