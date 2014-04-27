/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package io.asterisque;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Debug
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 * デバッグ用のユーティリティ機能です。
 *
 * @author Takami Torao
 */
public final class Debug {

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 * コンストラクタはクラス内に隠蔽されています。
	 */
	private Debug() {
	}

	// ==============================================================================================
	// インスタンスの文字列化
	// ==============================================================================================
	/**
	 * 指定されたインスタンスをデバッグ用に人間が読みやすい形式に変換します。
	 */
	public static String toString(Object value) {
		if(value == null) {
			return "null";
		}
		if(value instanceof Boolean) {
			return value.toString();
		}
		if(value instanceof Number) {
			return value.toString();
		}
		if(value instanceof Character) {
			return "\'" + escape((Character) value) + "\'";
		}
		if(value instanceof String) {
			String str = (String) value;
			return "\"" + str.chars().mapToObj(Debug::escape) + "\"";
		}
		if(value instanceof Map<?, ?>) {
			return "{" + String.join(",",
				((Map<?, ?>) value).entrySet().stream()
					.map(e -> new String[]{toString(e.getKey()), toString(e.getValue())})
					.sorted((a, b) -> a[0].compareTo(b[0]))
					.map(a -> a[0] + ":" + a[1])
					.toArray(String[]::new)
			) + "}";
		}
		if(value instanceof Collection<?>) {
			return "[" + String.join(",",
				(((Collection<?>) value).stream()
					.map(Debug::toString)
					.toArray(String[]::new)
				)
			) + "]";
		}
		if(value instanceof byte[]) {
			byte[] b = (byte[])value;
			StringBuilder buffer = new StringBuilder(b.length * 2);
			for(byte b1: b){
				buffer.append(String.format("%02X", b1 & 0xFF));
			}
			return buffer.toString();
		}
		if(value instanceof Object[]) {
			return toString(Arrays.asList((Object[]) value));
		}
		return value.toString();
	}

	// ==============================================================================================
	// 文字のエスケープ
	// ==============================================================================================
	/**
	 * 指定された文字をエスケープします。
	 */
	public static String escape(int ch) {
		switch(ch) {
			case '\0':
				return "\\0";
			case '\b':
				return "\\b";
			case '\f':
				return "\\f";
			case '\n':
				return "\\n";
			case '\r':
				return "\\r";
			case '\t':
				return "\\t";
			case '\\':
				return "\\\\";
			case '\'':
				return "\\\'";
			case '\"':
				return "\\\"";
			default:
				if(Character.isISOControl(ch) || !Character.isDefined(ch)) {
					return "\\u" + String.format("%04X", (int) ch);
				}
				return String.valueOf(ch);
		}
	}

}
