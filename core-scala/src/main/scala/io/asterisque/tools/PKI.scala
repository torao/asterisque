package io.asterisque.tools

import java.io._
import java.nio.charset.StandardCharsets

import io.asterisque.utils._
import org.slf4j.LoggerFactory

object PKI {

  /**
    * デフォルトの楕円曲線の形。
    */
  val DEFAULT_EC_CURVE = "prime256v1"

  /**
    * デフォルトの証明書有効期限 (日数)。
    */
  val DEFAULT_DAYS = 365

  // openssl コマンドが実行可能かを検証
  openssl(sh"""version""", silent = true)

  /**
    * 証明書に署名する CA (証明書認証局) の機能
    * ディレクトリにマッピングされ設定や CA 秘密鍵、CA 証明書などが保存されます。
    *
    * @param dir OpenSSL 実行のために CA 証明書などを保存するディレクトリ
    */
  class CA private[CA](val dir:File) {
    private[this] val confFile = new File(dir, "openssl.cnf")
    private[this] val caCertFile = new File(dir, "cacert.pem")
    private[this] val caKeyFile = new File(dir, "private/cakey.pem")
    private[this] val caReqFile = new File(dir, "careq.pem")

    private[this] val subdirs = Seq("certs", "crl", "newcerts", "private").map(name => new File(dir, name))

    private[this] val caCertPathFile = new File(dir, "cacertpath.pem")

    /** CA 証明書 */
    val certFile:File = caCertFile

    /** CA 秘密鍵 */
    val privateKeyFile:File = caKeyFile

    /** CA 証明書パス */
    val certPathFile:File = caCertPathFile

    /**
      * `CA.sh -newca` と同様にこのディレクトリに新しいルート CA を構築する。
      *
      * @param subject CA 証明書の Subject
      * @param ecCurve 楕円曲線の種類 (default: prime256v1)
      * @param days    CA キーペアが指定されていないときに新しく作成する証明書の有効期限
      * @return このインスタンス
      */
    private[CA] def initRoot(subject:String, ecCurve:String, days:Int):CA = {
      init()
      if(!caKeyFile.isFile) {
        openssl(sh"""req -new -nodes -newkey ec:<(openssl ecparam -name $ecCurve) -config $confFile -keyout $caKeyFile -out $caReqFile -subj $subject""")
        openssl(sh"""ca -config $confFile -create_serial -out $caCertFile -days $days -batch -keyfile $caKeyFile -selfsign -extensions v3_ca -infiles $caReqFile""")
      }
      if(!caCertPathFile.isFile) {
        IO.copy(caCertFile, caCertPathFile)
      }
      this
    }

    /**
      * この CA のディレクトリを中間 CA として初期化します。
      *
      * @param ca      親の CA
      * @param subject CA 証明書の Subject
      * @param ecCurve 楕円曲線の種類 (default: prime256v1)
      * @param days    CA キーペアが指定されていないときに新しく作成する証明書の有効期限
      * @return このインスタンス
      */
    private[CA] def initIntermediate(ca:CA, subject:String, ecCurve:String, days:Int):CA = {
      init()
      if(!caKeyFile.isFile) {
        ca.newPEMCertificateWithKey(caKeyFile, caCertFile, caCertPathFile, subject, ecCurve, days)
      }
      this
    }

    private[this] def init():Unit = {
      if(!dir.exists()) {
        dir.mkdirs()
      }
      subdirs.foreach(_.mkdirs())
      _init(confFile.getName) { file =>
        using(new FileWriter(file)) { out =>
          out.write(CA.CONF.replace("$DIR", s""""${Bash.unixPath(dir)}""""))
        }
      }
      _init(CA.INIT_TOUCH:_*)(IO.touch)
      _init(CA.INIT_ZEROS:_*) { file =>
        using(new FileWriter(file))(_.write("00"))
      }

      def _init(names:String*)(f:File => Unit):Unit = {
        names.map(name => new File(dir, name)).filter(!_.exists()).foreach(f)
      }
    }

    /**
      * この CA によって作成されたファイルを削除します。
      */
    def destroy():Unit = {
      (Seq(confFile, caKeyFile, caReqFile, caCertFile, caCertPathFile) ++ (CA.INIT_ZEROS ++ CA.INIT_TOUCH ++ CA.DESTROYS)
        .map(name => new File(dir, name))).filter(_.isFile).foreach(_.delete())
      subdirs.foreach(dir => IO.rmdirs(dir))
      if(Option(dir.listFiles()).exists(_.isEmpty)) {
        val _ = dir.delete()
      }
    }

    /**
      * 新しい ECDSA 秘密鍵とこの CA によって署名された X509 証明書を作成し PKCS#12 形式で保存します。
      *
      * @param pkcs12     ECDSA 秘密鍵と証明書の出力先 PKCS#12 ファイル
      * @param alias      秘密鍵のエイリアス
      * @param passphrase PKCS#12 ファイルのパスフレーズ
      * @param subject    証明書の Subject (e.g., "/C=JP/ST=Tokyo/L=Sumida/O=MyCompany Ltd./OU/Dev 1/CN=www.example.com")
      * @param ecCurve    楕円曲線の種類
      * @param days       証明書の有効期限
      */
    def newPKCS12KeyStore(pkcs12:File, alias:String, passphrase:String, subject:String, ecCurve:String = DEFAULT_EC_CURVE, days:Int = DEFAULT_DAYS):Unit = {
      val dir = pkcs12.getParentFile
      IO.temp(dir, s"ecdsa-$ecCurve-${pkcs12.getName}") { key =>
        IO.temp(dir, s"cert-${pkcs12.getName}") { cert =>
          newPEMCertificateWithKey(key, cert, null, subject, ecCurve, days)
          openssl(sh"""pkcs12 -export -inkey $key -in $cert -certfile $caCertPathFile -name $alias -passout pass:$passphrase -out $pkcs12""")
        }
      }
    }

    /**
      * 新しい ECDSA 秘密鍵とこの CA によって署名された X509 証明書をそれぞれ PEM 形式で保存します。
      *
      * @param keyPEM      秘密鍵の出力先
      * @param certPEM     X509 証明書の出力先
      * @param certPathPEM 証明書パスの出力先 (null の場合は出力しない)
      * @param subject     証明書の Subject (e.g., "/C=JP/ST=Tokyo/L=Sumida/O=MyCompany Ltd./OU/Dev 1/CN=www.example.com")
      * @param ecCurve     楕円曲線の種類
      * @param days        証明書の有効期限
      */
    private def newPEMCertificateWithKey(keyPEM:File, certPEM:File, certPathPEM:File, subject:String, ecCurve:String, days:Int):Unit = {
      IO.temp(keyPEM.getParentFile, s"csr-${certPEM.getName}") { csr =>
        openssl(sh"""req -new -sha256 -newkey ec:<(openssl ecparam -name $ecCurve) -batch -nodes -subj $subject -days $days -out $csr -keyout $keyPEM""")
        openssl(sh"""ca -config $confFile -in $csr -batch -days $days -out $certPEM""")
      }

      // 証明書パスの作成
      if(certPathPEM != null) {
        IO.copy(certPEM, certPathPEM)
        val _ = IO.append(caCertPathFile, certPathPEM)
      }
    }

    /**
      * この CA が発行した証明書を無効化します。
      *
      * @param certPEM 無効化する証明書
      */
    def revokeCertificate(certPEM:File):Unit = {
      // index.txt の先頭文字が R に更新される
      openssl(sh"""ca -config $confFile -revoke $certPEM""")
    }

    /**
      * この CA の CA 証明書パスと証明書取り消しリスト (CRL) を PKCS#7 形式でファイルに出力します。
      *
      * @param pkcs7File 出力先の PKCS#7 ファイル
      */
    def exportCertPathWithCRLAsPKCS7(pkcs7File:File):Unit = {
      IO.temp(pkcs7File.getParentFile, s"pkcs7-${pkcs7File.getName}") { crl =>
        exportCRLAsPEM(crl)
        openssl(sh"""crl2pkcs7 -in $crl -certfile $caCertPathFile -out $pkcs7File""")
      }
    }

    /**
      * この CA が取り消した証明書リスト (CRL) を PEM 形式でファイルに出力します。
      *
      * @param crlPEMFile 出力先の PEM ファイル
      */
    def exportCRLAsPEM(crlPEMFile:File):Unit = {
      openssl(sh"""ca -gencrl -config $confFile -out $crlPEMFile""")
    }
  }

  object CA {
    private[CA] val logger = LoggerFactory.getLogger(classOf[CA])
    private[this] val CONF_PATH = "/io/asterisque/tools/pki/openssl.cnf"
    private[CA] lazy val CONF = Option(getClass.getResourceAsStream(CONF_PATH))
      .map(in => new String(in.readAllBytes(), StandardCharsets.UTF_8))
      .getOrElse {
        throw new IllegalStateException(s"openssl configuration resource is not contained in resource: $CONF_PATH")
      }

    private[CA] lazy val INIT_ZEROS = Seq("serial", "crlnumber")
    private[CA] lazy val INIT_TOUCH = Seq("index.txt")
    private[CA] lazy val DESTROYS = Seq("index.txt.attr", "index.txt.old", "serial.old")

    /**
      * 既存の CA リポジトリを使用するインスタンスを構築します。
      *
      * @param dir CA リポジトリ
      * @return CA
      */
    def apply(dir:File):CA = new CA(dir)

    /**
      * 指定されたディレクトリに新しいルート CA リポジトリを作成します。
      *
      * @param dir     CA リポジトリ
      * @param subject CA 証明書の Subject
      * @param ecCurve 秘密鍵の楕円曲線アルゴリズム
      * @param days    CA 証明書の有効期限
      * @return CA
      */
    def newRootCA(dir:File, subject:String, ecCurve:String = DEFAULT_EC_CURVE, days:Int = DEFAULT_DAYS):CA = {
      new CA(dir).initRoot(subject, ecCurve, days)
    }

    /**
      * 指定されたディレクトリに新しい中間 CA リポジトリを作成します。
      *
      * @param issuer  CA 証明書を発行する親の CA
      * @param dir     CA リポジトリ
      * @param subject CA 証明書の Subject
      * @param ecCurve 秘密鍵の楕円曲線アルゴリズム
      * @param days    CA 証明書の有効期限
      * @return CA
      */
    def newIntermediateCA(issuer:CA, dir:File, subject:String, ecCurve:String = DEFAULT_EC_CURVE, days:Int = DEFAULT_DAYS):CA = {
      new CA(dir).initIntermediate(issuer, subject, ecCurve, days)
    }

    /**
      * 指定された PKCS#12 ファイルから証明書を取り出し PEM 形式で保存します。
      *
      * @param pkcs12File    証明書を取り出す PKCS#12 ファイル
      * @param outputPEMFile PEM 形式で証明書を保存する出力先ファイル
      */
    def exportCertificateAsPEM(pkcs12File:File, passphrase:String, outputPEMFile:File):Unit = {
      openssl(sh"""pkcs12 -in $pkcs12File -passin pass:$passphrase -nokeys -clcerts -out $outputPEMFile""")
    }
  }

  /**
    * PKCS#7 フォーマットのバイナリデータを PEM フォーマットに変換します。これにより JSSE 標準機能で
    * [[CA.exportCertPathWithCRLAsPKCS7()]] を使用して出力した CA 証明書パスと CRL を読み込むことができます。
    *
    * @param pkcs7Encoded PKCS#7 フォーマットのデータ
    * @return PEM フォーマットのデータ
    */
  def pkcs7ToPEM(pkcs7Encoded:Array[Byte]):Array[Byte] = {
    val in = new ByteArrayInputStream(pkcs7Encoded)
    val out = new ByteArrayOutputStream()
    openssl(sh"""pkcs7 -print_certs""", stdin = in, stdout = out)
    out.toByteArray
  }

  /**
    * 実行環境の OpenSSL コマンドを実行します。
    *
    * @param param  OpenSSL パラメータ
    * @param stdin  OpenSSL 標準入力へ渡すストリーム
    * @param stdout OpenSSL 標準出力から得たデータの出力先
    * @param silent 標準出力へ出力を行わない場合 true
    * @throws IllegalStateException OpenSSL コマンドが 0 以外を返した場合
    */
  @throws[IllegalStateException]
  private[tools] def openssl(param:Bash, stdin:InputStream = IO.NullInputStream, stdout:OutputStream = IO.NullOutputStream, silent:Boolean = false):Unit = this.synchronized {
    val cmd = sh"""openssl ${Bash.raw(param.toString)}"""
    val stderr = new ByteArrayOutputStream()
    val result = cmd.exec(silent = silent, stdin = stdin, stderr = stderr, stdout = stdout)
    if(result != 0) {
      throw new IllegalStateException(s"$cmd => $result\n${new String(stderr.toByteArray).trim()}")
    }
  }

}
