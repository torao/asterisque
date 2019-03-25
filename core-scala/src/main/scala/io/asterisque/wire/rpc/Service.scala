package io.asterisque.wire.rpc

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

import io.asterisque.utils.Debug
import io.asterisque.wire.rpc.Service._
import javax.annotation.Nonnull
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

trait Service extends ((Codec, Pipe, ExecutionContext) => Future[Any]) {

  def apply(@Nonnull codec:Codec, @Nonnull pipe:Pipe, @Nonnull executor:ExecutionContext):Future[Array[Byte]] = {
    execute(this, codec, pipe, executor) match {
      case Some(future) => future
      case None =>
        val msg = f"function not found: ${pipe.function & 0xFFFF}%d"
        Future.failed(new NoSuchFunctionException(msg))
    }
  }
}

object Service {
  private[this] val logger = LoggerFactory.getLogger(classOf[Service])

  private[this] val METHODS = new ConcurrentHashMap[Class[_], Map[Short, Method]]()

  private[Service] def execute(obj:Service, codec:Codec, pipe:Pipe, executor:ExecutionContext):Option[Future[Array[Byte]]] = {
    METHODS.computeIfAbsent(obj.getClass, { clazz:Class[_] =>
      Option(clazz.getMethods)
        .getOrElse(Array.empty)
        .collect { case method if method.isAnnotationPresent(classOf[Export]) =>
          val export = method.getAnnotation(classOf[Export])
          (export.value(), method)
        }.toMap
    }).get(pipe.function).map { method =>
      Future {
        call(obj, codec, pipe, method)
      }(executor)
    }
  }

  private[this] def call(obj:Service, codec:Codec, pipe:Pipe, method:Method):Array[Byte] = {

    // retrieve parameters
    val in = new ByteArrayInputStream(pipe.open.params)
    val params:Seq[Any] = codec.decode(in, method, isParams = true) match {
      case x:Array[_] => x.toSeq
      case x:Iterable[_] => x.toSeq
      case x:java.lang.Iterable[_] => x.asScala.toList
      case x => Seq(x)
    }
    if(params.size != method.getParameterCount) {
      throw new CodecException(s"parameter ${Debug.toString(params)} is incompatible with method ${Debug.getSimpleName(method)}; different size")
    }
    val args:Array[Object] = method.getParameterTypes.zip(params).map { case (clazz, param) =>
      Transformer.anyToObject(Transformer.transform(clazz, param))
    }
    val result = method.invoke(obj, args:_*)

    //
    val out = new ByteArrayOutputStream()
    codec.encode(out, method, isParams = false, result)
    out.toByteArray
  }
}