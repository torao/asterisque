/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package sample

import org.specs2.Specification

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// SampleSpec
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * サンプルコードが正しく動作することを確認するテスト
 *
 * @author Takami Torao
 */
class SampleSpec extends Specification { def is = s2"""
Samples should:
call by interface binding. $e0
call by dsl. $e1
messagng asynchronously. $e2
streaming synchronously. $e3
"""


	def e0 = synchronized {
		Sample1.main(Array())
		success
	}

	def e1 = synchronized {
		Sample2.main(Array())
		success
	}

	def e2 = synchronized {
		Sample3.main(Array())
		success
	}

	def e3 = synchronized {
		Sample4.main(Array())
		success
	}

}
