package io.asterisque.wire.rpc

import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method}
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap}

import io.asterisque.utils.Debug
import io.asterisque.wire.rpc.Skeleton._
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.Future


/**
  * リモート呼び出し先の function を @Export 定義されたメソッドとして扱うための動的プロキシ用ハンドラ。
  */
private[rpc] class Skeleton(logId:String, clazz:Class[_], open:(Byte, Short, Array[Any]) => Future[Any]) extends InvocationHandler {

  verifyServiceInterface(clazz)

  /**
    * リモートメソッドを呼び出します。
    *
    * @param proxy  プロキシオブジェクト
    * @param method 呼び出し対象のメソッド
    * @param args   メソッドの引数
    * @return 返し値
    */
  @throws[InvocationTargetException]
  @throws[IllegalAccessException]
  override def invoke(proxy:Object, method:Method, args:Array[Object]):Object = {
    val export = method.getAnnotation(classOf[Export])
    if(export == null) {
      // toString() や hashCode() など Object 型のメソッド呼び出し?
      logger.debug(s"$logId: normal method: ${Debug.getSimpleName(method)}")
      method.invoke(this, args)
    } else {
      // there is no way to receive block in interface binding
      logger.debug(s"$logId: calling remote method: ${Debug.getSimpleName(method)}")
      val priority = export.priority
      val function = export.value
      open(priority, function, if(args == null) Array.empty else args.map(_.asInstanceOf[Any]))
    }
  }
}

private[rpc] object Skeleton {
  private[Skeleton] val logger = LoggerFactory.getLogger(classOf[Skeleton])

  private[this] val verified = new ConcurrentHashMap[Class[_], String]()

  private[this] val FutureType:Set[Class[_]] = Set(classOf[CompletableFuture[_]], classOf[Future[_]])

  def verifyServiceInterface(clazz:Class[_]):Unit = {
    val msg = verified.computeIfAbsent(clazz, { _ =>
      val buffer = mutable.Buffer[String]()

      // 指定されたインターフェースのすべてのメソッドに @Export アノテーションが付けられていることを確認
      buffer ++= clazz.getDeclaredMethods.filter(_.getAnnotation(classOf[Export]) == null).map { method =>
        s"@${classOf[Export].getSimpleName} annotation is not specified on: ${Debug.getSimpleName(method)}"
      }

      // 指定されたインターフェースの全てのメソッドが Future または CompletableFuture の返値を持つことを確認
      buffer ++= clazz.getDeclaredMethods.filter(m => FutureType.contains(m.getReturnType)).map { method =>
        s"methods without return-type of Future[_] or CompletableFuture<?> exists: ${Debug.getSimpleName(method)}"
      }
      buffer.mkString("\n")
    })
    if(msg.nonEmpty) {
      throw new IllegalArgumentException(msg)
    }
  }

}