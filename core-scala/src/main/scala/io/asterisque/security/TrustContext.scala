package io.asterisque.security

import java.io.File

/**
  * 証明書の信頼性を検証するためのクラスです。
  */
class TrustContext private[TrustContext](dir:File) {

  /**
    * プライベートキーが保存されているディレクトリです。
    */
  val privateKeyDirectory:File = new File(dir, "private")

  /**
    * 信頼済み証明書が保存されているディレクトリです。
    */
  val trustedCertsDirectory:File = new File(dir, "trusted")

  /**
    * ブロック済み証明書または CRL が保存されているディレクトリです。
    */
  val blockedCertsDirectory:File = new File(dir, "blocked")

}

object TrustContext {

  /**
    * 指定されたディレクトリに新規の TrustContext 用ディレクトリを作成します。
    *
    * @param dir
    * @return
    */
  def newTrustContext(dir:File):TrustContext = {
    val context = new TrustContext(dir)

    // プライベートキーの作成
    context.privateKeyDirectory.mkdirs()

    context.trustedCertsDirectory.mkdirs()

    context
  }

}