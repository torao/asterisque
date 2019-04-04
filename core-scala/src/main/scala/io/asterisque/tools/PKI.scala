package io.asterisque.tools

import java.io.{File, FileWriter}
import java.nio.charset.StandardCharsets

import io.asterisque.carillon.using
import io.asterisque.utils._
import org.slf4j.LoggerFactory

object PKI {

  private[this] val DEFAULT_EC_CURVE = "prime256v1"

  // openssl が実行可能かを検証
  openssl(sh"""version""", silent = true)

  /**
    * 証明書に署名する CA (証明書認証局) の機能
    * ディレクトリにマッピングされ設定や CA 秘密鍵、CA 証明書などが保存されます。
    *
    * @param dir OpenSSL 実行のために CA 証明書などを保存するディレクトリ
    */
  class CA(val dir:File) {
    private[this] val confFile = new File(dir, "openssl.cnf")
    private[this] val caCertFile = new File(dir, "cacert.pem")
    private[this] val caKeyFile = new File(dir, "private/cakey.pem")
    private[this] val caReqFile = new File(dir, "careq.pem")

    private[this] val subdirs = Seq("certs", "crl", "newcerts", "private").map(name => new File(dir, name))


    /** CA 証明書 */
    val certFile:File = caCertFile

    /** CA 秘密鍵 */
    val privateKeyFile:File = caKeyFile

    /**
      * `CA.sh -newca` と同様にこのディレクトリに新しいルート CA を構築する。
      *
      * @param subject CA 証明書の Subject
      * @param ecCurve 楕円曲線の種類 (default: prime256v1)
      * @param days    CA キーペアが指定されていないときに新しく作成する証明書の有効期限
      */
    def initRoot(subject:String, ecCurve:String = DEFAULT_EC_CURVE, days:Int = 365):Unit = {
      init()
      if(!caKeyFile.isFile) {
        openssl(sh"""req -new -nodes -newkey ec:<(openssl ecparam -name $ecCurve) -config "$confFile" -keyout "$caKeyFile" -out "$caReqFile" -subj "$subject"""")
        openssl(sh"""ca -config "$confFile" -create_serial -out "$caCertFile" -days $days -batch -keyfile "$caKeyFile" -selfsign -extensions v3_ca -infiles "$caReqFile"""")
      }
    }

    /**
      * この CA のディレクトリを中間 CA として初期化します。
      *
      * @param ca      親の CA
      * @param subject CA 証明書の Subject
      * @param ecCurve 楕円曲線の種類 (default: prime256v1)
      * @param days    CA キーペアが指定されていないときに新しく作成する証明書の有効期限
      */
    def initIntermediate(ca:CA, subject:String, ecCurve:String = DEFAULT_EC_CURVE, days:Int = 365):Unit = {
      init()
      if(!caKeyFile.isFile) {
        ca.newPEMCertificateWithKey(caKeyFile, caCertFile, subject, ecCurve, days)
      }
    }

    private[this] def init():Unit = {
      if(!dir.exists()) {
        dir.mkdirs()
      }
      subdirs.foreach(_.mkdirs())
      _init(confFile.getName) { file =>
        using(new FileWriter(file)) { out =>
          out.write(CA.CONF.replace("$DIR", Bash.unixPath(dir)))
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
      (Seq(confFile, caKeyFile, caReqFile, caCertFile) ++ (CA.INIT_ZEROS ++ CA.INIT_TOUCH ++ CA.DESTROYS)
        .map(name => new File(dir, name))).filter(_.isFile).foreach(_.delete())
      subdirs.foreach(dir => IO.rmdirs(dir))
    }

    /**
      * 新しい ECDSA 秘密鍵とこの CA によって署名された X509 証明書を作成し PKCS#12 形式で保存します。
      *
      * @param pkcs12     ECDSA 秘密鍵と証明書の出力先 PKCS#12 ファイル
      * @param alias      秘密鍵のエイリアス
      * @param passphrase PKCS#12 ファイルのパスフレーズ
      * @param subject    証明書の Subject (e.g., "/C=JP/ST=Tokyo/L=Sumida/O=MyCompany Ltd./OU/Dev 1/CN=www.example.com")
      * @param ecCurve    楕円曲線の種類 (default: prime256v1)
      * @param days       証明書の有効期限 (default: 365)
      */
    def newPKCS12CertificateWithKey(pkcs12:File, alias:String, passphrase:String, subject:String, ecCurve:String = DEFAULT_EC_CURVE, days:Int = 365):Unit = {
      val dir = pkcs12.getParentFile
      IO.temp(dir, s"ecdsa-$ecCurve-${pkcs12.getName}") { key =>
        IO.temp(dir, s"cert-${pkcs12.getName}") { cert =>
          newPEMCertificateWithKey(key, cert, subject, ecCurve, days)
          openssl(sh"""pkcs12 -export -inkey "$key" -in "$cert" -name "$alias" -passout "pass:$passphrase" -out "$pkcs12"""")
        }
      }
    }

    /**
      * 新しい ECDSA 秘密鍵とこの CA によって署名された X509 証明書をそれぞれ PEM 形式で保存します。
      *
      * @param keyPEM  秘密鍵の出力先
      * @param certPEM X509 証明書の出力先
      * @param subject 証明書の Subject (e.g., "/C=JP/ST=Tokyo/L=Sumida/O=MyCompany Ltd./OU/Dev 1/CN=www.example.com")
      * @param ecCurve 楕円曲線の種類 (default: prime256v1)
      * @param days    証明書の有効期限 (default: 365)
      */
    private def newPEMCertificateWithKey(keyPEM:File, certPEM:File, subject:String, ecCurve:String = DEFAULT_EC_CURVE, days:Int = 365):Unit = {
      IO.temp(keyPEM.getParentFile, s"csr-${certPEM.getName}") { csr =>
        openssl(sh"""req -new -sha256 -newkey ec:<(openssl ecparam -name $ecCurve) -batch -nodes -subj "$subject" -days $days -out "$csr" -keyout "$keyPEM"""")
        openssl(sh"""ca -config "$confFile" -in "$csr" -batch -days $days -out "$certPEM"""")
      }
    }

  }

  private[this] object CA {
    private val logger = LoggerFactory.getLogger(classOf[CA])
    private[this] val CONF_PATH = "/io/asterisque/tools/pki/openssl.cnf"
    private lazy val CONF = Option(getClass.getResourceAsStream(CONF_PATH))
      .map(in => new String(in.readAllBytes(), StandardCharsets.UTF_8))
      .getOrElse {
        throw new IllegalStateException(s"openssl configuration resource is not contained in resource: $CONF_PATH")
      }

    private lazy val INIT_ZEROS = Seq("serial", "crlnumber")
    private lazy val INIT_TOUCH = Seq("index.txt")
    private lazy val DESTROYS = Seq("index.txt.attr", "index.txt.old", "serial.old")
  }

  /**
    * 実行環境の OpenSSL コマンドを実行します。
    *
    * @param param  OpenSSL パラメータ
    * @param input  コマンドプロンプトへの入力
    * @param silent 標準出力へ出力を行わない場合 true
    * @throws IllegalStateException OpenSSL コマンドが 0 以外を返した場合
    */
  @throws[IllegalStateException]
  private[tools] def openssl(param:Bash, input:String = "", silent:Boolean = false):Unit = this.synchronized {
    val cmd = "openssl"
    val result = sh"""$cmd ${param.toString}""".exec(input = input, silent = silent)
    if(result != 0) {
      throw new IllegalStateException(s"$cmd exit with $result")
    }
  }

}
