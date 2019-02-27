/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla

import java.io._
import org.slf4j._
import scala.collection.JavaConversions._
import java.lang.reflect.{Constructor, Method}
import java.security.cert.{X509Certificate, Certificate}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// asterisk
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
package object asterisk {
  import scala.language.reflectiveCalls
  private[this] val logger = LoggerFactory.getLogger("com.kazzla.asterisk")

  /**
   * リモート処理の呼び出しで発生した例外を表します。
   * @param message 例外メッセージ
   * @param ex 下層の例外
   */
  class RemoteException(message:String, ex:Throwable) extends Exception(message, ex) {
    def this(message:String) = this(message, null)
  }

  // ==============================================================================================
  // オブジェクトのクローズ
  // ==============================================================================================
  /**
   * `lock()` メソッドが定義されている任意のオブジェクトを例外なしでクローズします。
   * @param cs クローズするオブジェクト
   * @tparam T オブジェクトの型
   */
  def close[T <% { def close():Unit }](cs:T*):Unit = cs.filter{ _ != null }.foreach { c =>
    try {
      c.close()
    } catch {
      case ex:IOException =>
        logger.warn(s"fail to lock resource: $c", ex)
    }
  }

  // ==============================================================================================
  // リソーススコープの設定
  // ==============================================================================================
  /**
   * 指定された Closeable なリソースをラムダのスコープ付きで実行します。
   * @param resource ラムダが終了した時にクローズするリソース
   * @param f リソースを使用するラムダ
   * @tparam T リソースの型
   * @tparam U ラムダの返値
   * @return ラムダの返値
   */
  def using[T <% { def close():Unit }, U](resource:T)(f:(T)=>U):U = try {
    f(resource)
  } finally {
    close(resource)
  }

  // ==============================================================================================
  // デバッグ文字列の参照
  // ==============================================================================================
  /**
   * 指定された値をデバッグ出力に適切な文字列へ変換します。
   * @param value 文字列へ変換する値
   * @return デバッグ用文字列
   */
  def debugString(value:Any):String = value match {
    case null => "null"
    case i:Boolean => i.toString
    case i:Number => i.toString
    case i:Char => s"\'${escape(i)}\'"
    case i:String => i.map{ escape }.mkString("\"", "", "\"")
    case i:Map[_,_] => i.map{ case (k, v) => s"${debugString(k)}:${debugString(v)}" }.mkString("{", ",", "}")
    case i:java.util.Map[_,_] => debugString(i.toMap)
    case i:Seq[_] => i.map{ debugString }.mkString("[", ",", "]")
    case i:Array[_] => debugString(i.toSeq)
    case i:java.util.List[_] => debugString(i.toSeq)
    case i => i.toString
  }

  // ==============================================================================================
  // 文字のエスケープ
  // ==============================================================================================
  /**
   * 指定された文字を Java リテラル形式に変換します。
   * @param ch エスケープする文字
   * @return 文字列
   */
  private[this] def escape(ch:Char):String = ch match {
    case '\0' => "\\0"
    case '\b' => "\\b"
    case '\f' => "\\f"
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case '\\' => "\\\\"
    case '\'' => "\\\'"
    case '\"' => "\\\""
    case c if ! Character.isDefined(ch) | Character.isISOControl(ch) => f"\\u${c.toInt}%04X"
    case c => c.toString
  }

  /**
   * メソッドからデバッグ用の名前を取得するための拡張。
   * @param method メソッド
   */
  private[asterisk] implicit class RichMethod(method:Method){
    def getSimpleName:String = {
      method.getDeclaringClass.getSimpleName + "." + method.getName + "(" + method.getParameterTypes.map { p =>
        p.getSimpleName
      }.mkString(",") + "):" + method.getReturnType.getSimpleName
    }
  }

  /**
   * メソッドからデバッグ用の名前を取得するための拡張。
   * @param method メソッド
   */
  private[asterisk] implicit class RichConstructor(method:Constructor[_]){
    def getSimpleName:String = {
      method.getDeclaringClass.getSimpleName + "(" + method.getParameterTypes.map { p =>
        p.getSimpleName
      }.mkString(",") + ")"
    }
  }

  /**
   * ログ出力に証明書のダンプ機能を加える拡張。
   * @param l ログ出力先
   */
  private[asterisk] implicit class RichLogger(l:Logger){
    def dump(c:Certificate):Unit = if(l.isTraceEnabled){
      logger.trace(s"Algorithm: ${c.getPublicKey.getAlgorithm}")
      c match {
        case x:X509Certificate =>
          l.trace(s"Issuer : ${x.getIssuerX500Principal.getName}")
          l.trace(s"Subject: ${x.getSubjectX500Principal.getName}")
          l.trace(s"Valid  : ${x.getNotAfter}")
        case _ => None
      }
    }
  }

  /**
   * サブクラスで任意の属性値の設定/参照を行うためのトレイトです。
   */
  trait Attributes {

    /**
     * このインスタンスに関連づけられている属性値。
     */
    private[this] val attribute = new AtomicReference[Map[String,Any]](Map())

    /**
     * このインスタンスに属性値を設定します。
     * @param name 属性値の名前
     * @param obj 属性値
     * @return 以前に設定されていた属性値
     */
    def setAttribute(name:String, obj:Any):Option[Any] = {
      @tailrec
      def set():Option[Any] = {
        val map = attribute.get()
        val old = map.get(name)
        if(attribute.compareAndSet(map, map.updated(name, obj))){
          old
        } else {
          set()
        }
      }
      set()
    }

    /**
     * このインスタンスから属性値を参照します。
     * @param name 属性値の名前
     * @return 属性値
     */
    def getAttribute(name:String):Option[Any] = attribute.get().get(name)
  }

}
