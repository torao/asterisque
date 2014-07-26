/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Attributes
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * 任意の属性値の設定/参照を行います。
 * @author Takami Torao
 */
public final class Attributes {

	/**
	 * このインスタンスに関連づけられている属性値。
	 */
	private final ConcurrentMap<String,Object> attribute = new ConcurrentHashMap<>();

	public Attributes(){ }

	/**
	 * このインスタンスに属性値を設定します。
	 *
	 * @param name 属性値の名前
	 * @param obj  属性値
	 * @return 以前に設定されていた属性値
	 */
	public Optional<Object> setAttribute(String name, Object obj) {
		if(obj == null){
			throw new NullPointerException();
		}
		Object old = attribute.put(name, obj);
		if(old == null){
			return Optional.empty();
		}
		return Optional.of(old);
	}

	/**
	 * このインスタンスから属性値を参照します。
	 *
	 * @param name 属性値の名前
	 * @return 属性値
	 */
	public Optional<Object> getAttribute(String name) {
		Object obj = attribute.get(name);
		if(obj == null){
			return Optional.empty();
		}
		return Optional.of(obj);
	}

}