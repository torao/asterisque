/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package org.asterisque;

import java.nio.charset.Charset;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Asterisque
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * @author Takami Torao
 */
public final class Asterisque {

	// ==============================================================================================
	// コンストラクタ
	// ==============================================================================================
	/**
	 * コンストラクタはクラス内に隠蔽されています。
	 */
	private Asterisque() { }

	// ==============================================================================================
	// ID
	// ==============================================================================================
	/**
	 * ディレクトリ名や URI の一部に使用できる asterisque の識別子です。
	 */
	public static final String ID = "asterisque";


	// ==============================================================================================
	// UTF-8
	// ==============================================================================================
	/**
	 * UTF-8 を表す文字セットです。
	 */
	public static final Charset UTF8 = Charset.forName("UTF-8");

	// ==============================================================================================
	// スレッドグループ
	// ==============================================================================================
	/**
	 * asterisque が使用するスレッドの所属するグループです。
	 */
	public static final ThreadGroup threadGroup = new ThreadGroup(ID);

	// ==============================================================================================
	// スレッドファクトリの参照
	// ==============================================================================================
	/**
	 * 指定されたロールのためのスレッドファクトリを参照します。
	 */
	public static Thread newThread(String role, Runnable r){
		return new Thread(threadGroup, r, ID + "." + role);
	}

	// ==============================================================================================
	// スレッドファクトリの参照
	// ==============================================================================================
	/**
	 * 指定されたロールのためのスレッドファクトリを参照します。
	 */
	public static ThreadFactory newThreadFactory(String role){
		return r -> newThread(role, r);
	}

	public static final UUID Zero = new UUID(0, 0);


	public static final class Protocol {
		private Protocol(){ }

		/**
		 * プロトコル識別子兼エンディアン確認用の 2 バイトのマジックナンバー。ASCII コードで "*Q" の順でバイナリスト
		 * リームの先頭に出現しなければならない。
		 */
		public static final short Signature = 0x2A51;

		/** プロトコルバージョン 0.1 を表す数値 */
		public static final short Version_0_1 = 0x0001;

		/** 現在の実装がサポートしているバージョン */
		public static final short CurrentVersion = Version_0_1;
	}

	public static String logPrefix(){ return "-:--------"; }
	public static String logPrefix(boolean isServer){ return logPrefix(isServer, null); }
	public static String logPrefix(boolean isServer, UUID id){
		return (isServer? 'S': 'C') + ":" + (id == null? "--------": id.toString().substring(0, 8).toUpperCase());
	}
}
