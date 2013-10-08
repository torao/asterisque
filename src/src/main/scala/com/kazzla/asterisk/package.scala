/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla

import java.io._
import org.slf4j._
import java.net.{InetSocketAddress, SocketAddress}
import java.security.{DigestInputStream, MessageDigest}
import java.lang.reflect.Method
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

	def close[T <% { def close():Unit }](cs:T*):Unit = cs.filter{ _ != null }.foreach { c =>
		try {
			c.close()
		} catch {
			case ex:IOException =>
				logger.warn(s"fail to close resource: $c", ex)
		}
	}

	def using[T <% { def close():Unit }, U](resource:T)(f:(T)=>U):U = try {
		f(resource)
	} finally {
		close(resource)
	}

	implicit class RichSocketAddress(addr:SocketAddress) {
		def getName:String = addr match {
			case i:InetSocketAddress =>
				s"${i.getAddress.getHostAddress}:${i.getPort}"
			case s => s.toString
		}
	}

	implicit class RichInputStream(in:InputStream) {

		/**
		 * メッセージダイジェストの算出
		 */
		def digest(algorithm:String):Array[Byte] = {
			val md = MessageDigest.getInstance(algorithm)
			val is = new DigestInputStream(in, md)
			val buffer = new Array[Byte](1024)
			while(is.read(buffer) > 0){
				None
			}
			md.digest()
		}
	}

	implicit class RichMethod(method:Method){
		def getSimpleName:String = {
			method.getDeclaringClass.getSimpleName + "." + method.getName + "(" + method.getParameterTypes.map { p =>
				p.getSimpleName
			}.mkString(",") + "):" + method.getReturnType.getSimpleName
		}
	}

	def debugString(value:Any):String = value match {
		case null => "null"
		case i:Boolean => i.toString
		case i:Number => NumberFormat.getNumberInstance.format(i)
		case i:Char => s"\'${escape(i)}\'"
		case i:String => i.map{ escape }.mkString("\"", "", "\"")
		case i:Map[_,_] => i.map{ case (key, value) => s"${debugString(key)}:${debugString(value)}" }.mkString("{", ",", "}")
		case i:java.util.Map[_,_] => debugString(i.toMap)
		case i:Seq[_] => i.map{ debugString }.mkString("[", ",", "]")
		case i:Array[_] => debugString(i.toSeq)
		case i:java.util.List[_] => debugString(i.toSeq)
		case i => i.toString
	}

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
