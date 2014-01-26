/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import org.slf4j.LoggerFactory
import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.util.{Failure, Success}
import ExecutionContext.Implicits.global
import java.io.{IOException, InputStream, OutputStream}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Service
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
trait Service {
	import Service._

	private[this] var functions = Map[Short,Function]()

	private[asterisk] def dispatch(pipe:Pipe, msg:Message):Unit = {
		msg match {
			case Open(_, _, params) =>
				functions.get(pipe.function) match {
					case Some(func) =>
						pipe.onSuccess ++ { result => func.disconnect(result, null) }
						pipe.onFailure ++ { ex => func.disconnect(null, ex.toString) }
						func(params).onComplete {
							case Success(result) => pipe.close(result)
							case Failure(ex) =>
								logger.error(s"unexpected exception: $ex", ex)
								pipe.close(ex)
						}
					case None =>
						logger.debug(s"function unbound on: ${pipe.function}, $pipe, $msg")
						if(msg.isInstanceOf[Open]){
							pipe.close(new RemoteException(s"function unbound on: ${pipe.function}"))
						}
				}
			case block:Block => pipe.block(block)
			case close:Close[_] => pipe.close(close)
		}
	}

	protected implicit class FunctionIdentifier(function:Int){
		if(function.toShort != function){
			throw new IllegalStateException(s"function id out of range for Short: $function")
		}
		def accept(f:(Seq[Any])=>Future[Any]):Function = {
			if(functions.contains(function.toShort)){
				throw new IllegalArgumentException(s"function $function already defined")
			}
			val func = new Function(f)
			functions = functions.updated(function.toShort, func)
			func
		}
	}

}

object Service {
	private[Service] val logger = LoggerFactory.getLogger(classOf[Service])

	class Function private[Service](onAccept:(Seq[Any])=>Future[Any]) {
		private[this] var _onDisconnect:Option[(Any)=>Unit] = None
		def disconnect(f:(Any)=>Unit):Function = {
			_onDisconnect = Some(f)
			this
		}
		private[Service] def apply(params:Seq[Any]):Future[Any] = onAccept(params)
		private[Service] def disconnect(result:Any, error:String) = _onDisconnect.foreach { _(result) }
	}

}

abstract class SyncService(implicit executor:ExecutionContext) extends Service {
	import SyncService.logger

	logger.debug(s"binding ${getClass.getSimpleName} as service")

	/**
	 * サービスインスタンスから抽出した function 番号が定義されているメソッドのマップ。
	 */
	getClass.getInterfaces.foreach{
		_.getDeclaredMethods.filter{ _.getAnnotation(classOf[Export]) != null }.foreach { m =>
			val id = m.getAnnotation(classOf[Export]).value()
			logger.debug(s"  function $id to ${m.getSimpleName}")
			id.toInt accept { (args) =>
				val promise = Promise[Any]()
				val session = Session()
				val pipe = Pipe()
				val stream = pipe.get.prepareStream(m.getAnnotation(classOf[Export]).stream())
				executor.execute(new Runnable {
					def run(): Unit = try {
						Session.using(session.get, pipe.get){
							pipe.get.useStream(stream){
								val params = TypeMapper.appropriateValues(args, m.getParameterTypes)
								promise.success(m.invoke(SyncService.this, params:_*))
							}
						}
					} catch {
						case ex:Throwable =>
							promise.failure(ex)
							if(ex.isInstanceOf[ThreadDeath]){
								throw ex
							}
					}
				})
				promise.future
			}
		}
	}

	implicit class PipeStream(pipe:Pipe){
		private[SyncService] def prepareStream(us:Boolean):Option[(InputStream,OutputStream)] = if(us){
			val in = new PipeInputStream()
			val out = new PipeOutputStream(pipe)
			pipe.onBlock ++ in
			Some(in, out)
		} else {
			None
		}
		private[SyncService] def useStream[T](s:Option[(InputStream,OutputStream)])(f: =>T):T = {
			SyncService.stream.set(s)
			try {
				f
			} finally {
				SyncService.stream.set(null)
			}
		}
		def in:InputStream = getStream._1
		def out:OutputStream = getStream._2
		private[this] def getStream:(InputStream,OutputStream) = Option(SyncService.stream.get()) match {
			case Some(s) if s.isDefined => s.get
			case _ =>
				throw new IOException(s"function is not declared with @${classOf[Export].getSimpleName}(stream=true), or out of scope")
		}
	}

}

object SyncService {
	private[SyncService] val logger = LoggerFactory.getLogger(classOf[SyncService])

	private[SyncService] val stream = new ThreadLocal[Option[(InputStream,OutputStream)]]()

}

class Hoge extends Service {

	1024 accept { (args) =>
		Future.successful(null)
	}

}