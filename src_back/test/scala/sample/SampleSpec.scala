/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package sample

import org.specs2.Specification
import org.slf4j.LoggerFactory

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

	val logger = LoggerFactory.getLogger(classOf[SampleSpec])

	def e0 = SampleSpec.synchronized {
		logger.info("e0 -------------------------------------------------------------")
		Sample1.main(Array())
		success
	}

	def e1 = SampleSpec.synchronized {
		logger.info("e1 -------------------------------------------------------------")
		Sample2.main(Array())
		success
	}

	def e2 = SampleSpec.synchronized {
		logger.info("e2 -------------------------------------------------------------")
		Sample3.main(Array())
		success
	}

	def e3 = SampleSpec.synchronized {
		logger.info("e3 -------------------------------------------------------------")
		Sample4.main(Array())
		success
	}

}

object SampleSpec {
}