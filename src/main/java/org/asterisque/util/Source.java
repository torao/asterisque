/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

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
public abstract class Source<A> {
	private static final Logger logger = LoggerFactory.getLogger(Source.class);

	// Future を返すメソッドは終端操作

	private final Collection<Consumer<Traverse<A>>> operations = new ArrayList<>();

	private volatile long _count = 0;

	// TODO 処理設定時に currentCount が 0 でなければ通知漏れの発生として例外にする
	public long currentCount(){ return _count; }

	private void addOperation(Consumer<Traverse<A>> f) {
		onAddOperation();
		operations.add(f);
	}

	/**
	 * オペレーションを追加できるかを
	 */
	protected void onAddOperation() {
		if(currentCount() > 0){
			throw new IllegalStateException("unable to add operation, " + currentCount() + " elements are already arrived");
		}
	}

	public <B> Source<B> map(Function<A,B> f){
		Joint<B> src = new Joint<>();
		addOperation(t -> {
			if(t instanceof Sequence) {
				src.sequence(f.apply(((Sequence<A>)t).value));
			} else if(t instanceof Finish) {
				src.finish();
			} else if(t instanceof Failure){
				src.failure(((Failure)t).cause);
			}
		});
		return src;
	}

	public Source<A> filter(Predicate<A> f) {
		Joint<A> src = new Joint<>();
		addOperation(t -> {
			if(t instanceof Sequence) {
				if(f.test(((Sequence<A>) t).value)) {
					src.traverse(t);
				}
			} else {
				src.traverse(t);
			}
		});
		return src;
	}

	public Source<A> filterNot(Predicate<A> f){
		return filter(e -> ! f.test(e));
	}

/*
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
		addOperation(g)
		src
	}
*/

	public synchronized CompletableFuture<Void> foreach(Consumer<A> f) {
		CompletableFuture<Void> promise = new CompletableFuture<>();
		addOperation(t -> {
			if(t instanceof Sequence) {
				f.accept(((Sequence<A>) t).value);
			} else if(t instanceof Finish) {
				promise.complete(null);
			} else if(t instanceof Failure){
				promise.completeExceptionally(((Failure)t).cause);
			}
		});
		return promise;
	}

	public CompletableFuture<Optional<A>> reduceLeftOption(BiFunction<A,A,A> op){
		final AtomicReference<Optional<A>> result = new AtomicReference<>(Optional.empty());
		return foreach( value -> {
			if(! result.get().isPresent()) {
				result.set(Optional.of(value));
			} else {
				result.set(Optional.of(op.apply(result.get().get(), value)));
			}
		}).thenApply(x -> result.get());
	}

	public CompletableFuture<A> reduceLeft(BiFunction<A,A,A> f){
		CompletableFuture<A> promise = new CompletableFuture<>();
		reduceLeftOption(f).whenComplete((op, ex) -> {
			if(op != null){
				if(op.isPresent()) {
					promise.complete(op.get());
				} else {
					promise.completeExceptionally(new UnsupportedOperationException("empty source"));
				}
			} else if(ex != null){
				promise.completeExceptionally(ex);
			} else {
				throw new IllegalStateException();
			}
		});
		return promise;
	}

	public CompletableFuture<A> reduce(BiFunction<A,A,A> op) { return reduceLeft(op); }
	public CompletableFuture<Optional<A>> reduceOption(BiFunction<A,A,A> op){ return reduceLeftOption(op); }

	public <B> CompletableFuture<B> foldLeft(B z, BiFunction<B,A,B> f) {
		// 試験的に並列実行対応
		AtomicReference<B> result = new AtomicReference<>(z);
		return foreach( value -> {
			while(true){
				B r0 = result.get();
				B r1 = f.apply(r0, value);
				if(result.compareAndSet(r0, r1)){
					break;
				}
			}
		}).thenApply(x -> result.get());
	}

	public CompletableFuture<A> fold(A z, BiFunction<A,A,A> op){ return foldLeft(z, op); }

	public CompletableFuture<Optional<A>> find(Function<A,Boolean> p){
		CompletableFuture<Optional<A>> promise = new CompletableFuture<>();
		addOperation(t -> {
			if(t instanceof Sequence) {
				if(!promise.isDone() && p.apply(((Sequence<A>) t).value)) {
					promise.complete(Optional.of(((Sequence<A>) t).value));
				}
			} else if(t instanceof Finish){
				if(! promise.isDone()){
					promise.complete(Optional.empty());
				}
			} else if(t instanceof Failure){
				promise.completeExceptionally(((Failure)t).cause);
			}
		});
		return promise;
	}

	public CompletableFuture<Boolean> exists(Function<A,Boolean> p){ return find(p).thenApply(Optional::isPresent); }

	public <K> CompletableFuture<Map<K,List<A>>> groupBy(Function<A,K> f) {
		return foldLeft(new HashMap<K,List<A>>(), (map, value) -> {
			K key = f.apply(value);
			if(map.containsKey(key)) {
				List<A> values = map.get(key);
				values.add(value);
			} else {
				List<A> values = new ArrayList<>();
				values.add(value);
				map.put(key, values);
			}
			return map;
		});
	}

/*
	def maxBy[B](f:(A)=>B)(implicit cmp:Ordering[B]):Future[A] = reduceLeft{ (x, y) => if (cmp.gteq(f(x), f(y))) x else y }
	def max[B>:A](implicit cmp:Ordering[B]):Future[A] = reduceLeft{ (x, y) => if (cmp.gteq(x, y)) x else y }
	def minBy[B](f:(A)=>B)(implicit cmp:Ordering[B]):Future[A] = maxBy(f)(cmp.reverse)
	def min[B>:A](implicit cmp:Ordering[B]):Future[A] = max(cmp.reverse)
*/

	public <B> CompletableFuture<B> aggregate(B z, BiFunction<B,A,B> seqop, BiFunction<B,B,B> combop) {
		return foldLeft(z, seqop);
	}

/*
	def sum[B >: A](implicit num: Numeric[B]):Future[B] = foldLeft(num.zero)(num.plus)
	def product[B>:A](implicit num:Numeric[B]):Future[B] = foldLeft(num.one)(num.times)
*/
	public CompletableFuture<String> mkString(String start, String sep, String end) {
		return foldLeft(new StringBuilder(), (x, y) -> {
			if(x.length() != 0){
				x.append(sep);
			}
			return x.append(y);
		}).thenApply( x -> start + x.toString() + end );
	}

	public CompletableFuture<String> mkString(String sep){ return mkString("", sep, ""); }
	public CompletableFuture<String> mkString(){ return mkString(""); }

/* タプルが必要
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
		addOperation(g)
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
*/

	public CompletableFuture<Long> count(Predicate<A> f) {
		return foldLeft(0L, (total,elem) -> total + (f.test(elem)? 1: 0));
	}

/*
	@deprecated("experimental")
	def par(implicit ec:ExecutionContext):Source[A] = {
		val src = new ParallelJoint[A]()
		val g:(Traverse[A])=>Unit = { src.traverse }
		addOperation(g)
		src
	}
*/

	void traverse(Traverse<A> elem) {
		if(operations.isEmpty()){
			throw new UnsupportedOperationException("incomplete combinator chain");
		} else {
			operations.forEach(op -> {
				try {
					op.accept(elem);
				} catch(ThreadDeath ex) {
					throw ex;
				} catch(Throwable ex){
					try {
						op.accept(new Failure<>(ex));
					} catch(Throwable e){
						logger.error("", e);
					}
					throw ex;
				}
			});
			if(elem instanceof Sequence){
				_count += 1;
			} else {
				operations.clear();
				operations.add(x -> {});
			}
		}
	}

	protected void sequence(A value){ traverse(new Sequence<A>(value)); }
	protected void finish(){ traverse(new Finish<A>()); }
	protected void failure(Throwable cause){ traverse(new Failure<>(cause)); }

	public static <A,B> CompletableFuture<B> apply(Iterable<A> t, Function<Source<A>,CompletableFuture<B>> op, Executor exec) {
		class X extends Source<A> {
			public void start(){
				exec.execute(() -> {
					t.forEach(x -> sequence(x) );
					finish();
				});
			}
		}
		X s = new X();
		CompletableFuture<B> f = op.apply(s);
		s.start();
		return f;
	}

}

class Joint<A> extends Source<A> {
	private volatile boolean end = false;
	public void traverse(Traverse<A> elem) {
		if(! end){
			try {
				super.traverse(elem);
			} catch(Throwable ex){
				end = true;
				throw ex;
			}
		}
		if(elem instanceof Finish || elem instanceof  Failure){
			end = true;
		}
	}
}

/*
class ParallelJoint[A](implicit ec:ExecutionContext) extends Joint[A] {
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
*/

abstract class Traverse<A>{ }
final class Sequence<A> extends Traverse<A> {
	public final A value;
	public Sequence(A value){ this.value = value; }
}
final class Finish<A> extends Traverse<A> { }
final class Failure<A> extends Traverse<A> {
	public final Throwable cause;
	public Failure(Throwable cause){ this.cause = cause; }
}
