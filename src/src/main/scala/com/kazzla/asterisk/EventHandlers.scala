/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package com.kazzla.asterisk

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// EventHandlers
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * イベント発生時に実行する処理を保持するクラス。
 * @author Takami Torao
 */
final class EventHandlers[T] {
	private[this] val listeners = new AtomicReference(Seq[(T)=>Unit]())

	/**
	 * 指定されたイベントハンドラを追加します。
	 */
	@tailrec
	def ++(f:(T)=>Unit):EventHandlers[T] = {
		val l = listeners.get()
		if(! listeners.compareAndSet(l, l.+:(f))){
			++(f)
		} else {
			this
		}
	}

	/**
	 * 指定されたイベントハンドラを追加します。
	 */
	def apply(f:(T)=>Unit) = ++(f)

	/**
	 * 指定されたイベントハンドラを削除します。
	 */
	@tailrec
	def --(f:(T)=>Unit):EventHandlers[T] = {
		val l = listeners.get()
		if(! listeners.compareAndSet(l, l.filter{ _ != f })){
			--(f)
		} else {
			this
		}
	}

	/**
	 * すべてのイベントハンドラに通知を行います。
	 */
	def apply(s:T):Unit = listeners.get().foreach{ _(s) }
}
