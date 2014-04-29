/*
 * Copyright (c) 2013 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// EventHandlers
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * イベント発生時に実行する処理を保持するクラス。
 * @author Takami Torao
 */
public final class EventHandlers<T> implements Consumer<T> {
	private static final Logger logger = LoggerFactory.getLogger(EventHandlers.class);

	/**
	 * このインスタンスが呼び出し対象としているイベントハンドラ。
	 */
	private final AtomicReference<List<Consumer<T>>> listeners = new AtomicReference<>(new ArrayList<>());

	// ==============================================================================================
	// イベントハンドラの追加
	// ==============================================================================================
	/**
	 * 指定されたイベントハンドラを追加します。
	 * @param f 追加するイベントハンドラ
	 */
	public EventHandlers<T> add(Consumer<T> f) {
		List<Consumer<T>> oldList = listeners.get();
		List<Consumer<T>> newList = new ArrayList<>(oldList);
		newList.add(f);
		if(!listeners.compareAndSet(oldList, newList)) {
			return add(f);
		}
		return this;
	}

	// ==============================================================================================
	// イベントハンドラの追加
	// ==============================================================================================
	/**
	 * 指定されたイベントハンドラセットのハンドラをすべてこのインスタンスに設定します。
	 * @param h ハンドラを取り込むハンドラ
	 */
	public EventHandlers<T> addAll(EventHandlers<T> h) {
		List<Consumer<T>> oldList = listeners.get();
		List<Consumer<T>> newList = new ArrayList<>(oldList);
		newList.addAll(h.listeners.get());
		if(!listeners.compareAndSet(oldList, newList)) {
			return addAll(h);
		}
		return this;
	}

	// ==============================================================================================
	// イベントハンドラの削除
	// ==============================================================================================
	/**
	 * 指定されたイベントハンドラを削除します。
	 * @param f 削除するイベントハンドラ
	 */
	public EventHandlers<T> remove(Consumer<T> f){
		List<Consumer<T>> oldList = listeners.get();
		List<Consumer<T>> newList = new ArrayList<>(oldList);
		newList.remove(f);
		if(oldList.size() > newList.size() && !listeners.compareAndSet(oldList, newList)) {
			return add(f);
		}
		return this;
	}

	// ==============================================================================================
	// イベントハンドラへの通知
	// ==============================================================================================
	/**
	 * このインスタンスに登録されているすべてのイベントハンドラに引数 `s` で通知を行います。
	 * @param s イベントハンドラの呼び出しパラメータ
	 */
	@Override
	public void accept(T s){
		for(Consumer<T> l: listeners.get()){
			try {
				l.accept(s);
				if(logger.isTraceEnabled()){
					logger.trace(String.format("callback %s(%s)", l.getClass().getSimpleName(), s));
				}
			} catch(Throwable ex){
				logger.error(String.format("unexpected exception on calling %s(%s)", l.getClass().getSimpleName(), s));
			}
		}
	}
}
