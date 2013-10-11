/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla

import java.io._
import org.slf4j._
import java.text.NumberFormat
import scala.collection.JavaConversions._

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// asterisk
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
package object asterisk {
	import scala.language.reflectiveCalls
	private[this] val logger = LoggerFactory.getLogger("com.kazzla.asterisk")

	// ==============================================================================================
	// オブジェクトのクローズ
	// ==============================================================================================
	/**
	 * `close()` メソッドが定義されている任意のオブジェクトを例外なしでクローズします。
	 * @param cs クローズするオブジェクト
	 * @tparam T オブジェクトの型
	 */
	def close[T <% { def close():Unit }](cs:T*):Unit = cs.filter{ _ != null }.foreach { c =>
		try {
			c.close()
		} catch {
			case ex:IOException =>
				logger.warn(s"fail to close resource: $c", ex)
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
		case i:Number => NumberFormat.getNumberInstance.format(i)
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

}
