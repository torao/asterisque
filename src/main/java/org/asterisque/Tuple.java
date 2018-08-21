/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Tuple
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

import java.io.Serializable;

/**
 * インデックスによるアクセスが可能なフィールドを持つタプルを表すインターフェースです。
 * Scala の Product や Tuple に相当します。
 *
 * @author Takami Torao
 */
public interface Tuple {

	// ==============================================================================================
	// 最大プロパティ数
	// ==============================================================================================
	/**
	 * Void または Scala の Unit, () を示す定数です。
	 */
	public static final Tuple Void = new _Void();

	public static final class _Void implements Tuple, Serializable {
		@Override
		public String schema() { return "void"; }
		@Override
		public int count() { return 0; }
		@Override
		public Object valueAt(int i) { return null; }
		@Override
		public String toString() { return "void"; }
		@Override
		public int hashCode() { return 0; }
		@Override
		public boolean equals(Object obj) { return obj instanceof  _Void; }
	}

	// ==============================================================================================
	// 最大プロパティ数
	// ==============================================================================================
	/**
	 * シリアライズ可能なタプル型のプロパティ数上限です。
	 * Scala では case class のプロパティ数に相当します。
	 */
	public static int MaxFields = 0xFF;

	// ============================================================================================
	// スキーマ名
	// ============================================================================================
	/**
	 * この構造体の表すスキーマ名です。Java 系の言語ではクラス名に相当します。
	 */
	public String schema();

	// ============================================================================================
	// フィールド数
	// ============================================================================================
	/**
	 * この構造体のフィールド数を参照します。
	 */
	public int count();

	// ============================================================================================
	// フィールド値の参照
	// ============================================================================================
	/**
	 * 指定されたインデックスのフィールド値を参照します。
	 */
	public Object valueAt(int i);

}
