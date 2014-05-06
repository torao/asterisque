/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Attributes
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * サブクラスで任意の属性値の設定/参照を行うためのトレイトです。
 * @author Takami Torao
 */
public abstract class Attributes {

	protected Attributes(){ }

	/**
	 * このインスタンスに関連づけられている属性値。
	 */
	private AtomicReference<Map<String, Object>> attribute = new AtomicReference<>();

	/**
	 * このインスタンスに属性値を設定します。
	 *
	 * @param name 属性値の名前
	 * @param obj  属性値
	 * @return 以前に設定されていた属性値
	 */
	public Optional<Object> setAttribute(String name, Object obj) {
		Map<String, Object> map = attribute.getAndUpdate(oldMap -> {
			Map<String, Object> newMap = new HashMap<String, Object>(oldMap);
			newMap.put(name, obj);
			return newMap;
		});
		return get(map, name);
	}

	/**
	 * このインスタンスから属性値を参照します。
	 *
	 * @param name 属性値の名前
	 * @return 属性値
	 */
	public Optional<Object> getAttribute(String name) {
		Map<String, Object> map = attribute.get();
		return get(map, name);
	}

	private static Optional<Object> get(Map<String, Object> map, String name) {
		if(map.containsKey(name)) {
			return Optional.of(map.get(name));
		} else {
			return Optional.empty();
		}
	}
}