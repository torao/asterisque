package io.asterisque.tools.pki

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.PrivateKey
import java.security.cert.X509Certificate

import io.asterisque.auth.Algorithms
import io.asterisque.carillon.using
import io.asterisque.tools._
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

@deprecated("use io.asterisque.tools.PKI.CA")
class CA(val dir:File) {
  private[CA] val CONF = new File(dir, "openssl.cnf")
  private[CA] val CACERT = new File(dir, "cacert.pem")
  private[this] val SERIAL = new File(dir, "serial")
  private[CA] val CAKEY = new File(dir, "private/cakey.pem")
  private[CA] val CAREQ = new File(dir, "careq.pem")

  // openssl が実行可能かを検証
  openssl(sh"""version""", silent = true)

  /** CA 証明書。 */
  val certFile:File = CACERT

  def cert:X509Certificate = Algorithms.Certificate.load(certFile)

  val privateKeyFile:File = CAKEY

  def privateKey:PrivateKey = Algorithms.PrivateKey.load(privateKeyFile)

  /**
    * `CA.sh -newca` と同様にこのディレクトリに新しい CA を構築する。
    *
    * @param cadays CA キーペアが指定されていないときに新しく作成する証明書の有効期限
    */
  def init(cadays:Int = 365):Unit = {
    if(!CONF.isFile) {
      CONF.getParentFile.mkdirs()
      val conf = "io/asterisque/tools/pki/openssl.cnf"
      using(new FileWriter(CONF)) { out =>
        out.write(new String(using(getClass.getClassLoader.getResourceAsStream(conf)) { in =>
          if(in == null) {
            throw new FileNotFoundException(s"resource not found: $conf")
          }
          in.readAllBytes()
        }, StandardCharsets.UTF_8).replaceAll("\\$DIR", sh"$dir".toString))
      }
    }

    if(!SERIAL.isFile) {
      Seq("certs", "crl", "newcerts", "private").foreach(name => new File(dir, name).mkdirs())
      new FileOutputStream(new File(dir, "index.txt")).close()
      using(new FileWriter(new File(dir, "crlnumber")))(_.write("00"))
    }

    if(!CAKEY.isFile) {
      CA.logger.info("Making CA certificate ...")
      newKeyWithSelfSignedCertificate(CAKEY, CAREQ, CACERT, "Carillon Unit Test CA", cadays)
    }
  }

  def newKeyWithSelfSignedCertificate(key:File, csr:File, cert:File, cname:String = "", days:Int = 365):Unit = {
    openssl(sh"""req -new -nodes -newkey ec:<(openssl ecparam -name prime256v1) -config "$CONF" -keyout "$key" -out "$csr" -subj "/C=JP/ST=Tokyo/L=Sumida/O=Carillon/OU=QA Section/CN=$cname"""")
    openssl(sh"""ca -config "$CONF" -create_serial -out "$cert" -days $days -batch -keyfile "$key" -selfsign -extensions v3_ca -infiles "$csr"""")
  }

  def newKey(key:File):Unit = openssl(sh"""ecparam -genkey -name prime256v1 -noout -out "$key"""")

  def newCertificate(key:File, cert:File, subject:String):Unit = newCertificate(key, cert, subject, 365)

  def newCertificate(keyIn:File, certOut:File, subject:String, days:Int):Unit = {
    val csr = File.createTempFile("csr-", ".tmp", certOut.getParentFile)
    openssl(sh"""req -new -sha256 -key "$keyIn" -out "$csr" -multivalue-rdn -subj "$subject"""")
    openssl(sh"""x509 -req -CAkey "$CAKEY" -ca "$certFile" -CAcreateserial -in "$csr" -out "$certOut" -days $days -sha256""")
    csr.delete()
  }

  def newCertificate(caKey:File, caCert:File, csr:File, cert:File, days:Int = 365):Unit = {
    openssl(sh"""x509 -req -CAkey "$caKey" -ca "$caCert" -CAcreateserial -in "$csr" -out "$cert" -days $days -sha256 """)
  }

  def newKeyWithCertificate(keyOut:File, certOut:File, subject:String, days:Int = 365):Unit = {
    val csr = File.createTempFile("temp-", ".crs", certOut.getParentFile)
    openssl(sh"""req -new -sha256 -newkey ec:<(openssl ecparam -name prime256v1) -batch -nodes -multivalue-rdn -subj "$subject" -out "$csr" -days $days -keyout "$keyOut"""")
    openssl(sh"""ca -config "$CONF" -batch -days $days -out "$certOut" -infiles "$csr"""")
    csr.delete()
  }

  def newPKCS7CertChain(certs:Seq[File], pkcs7:File):Unit = {
    val crl = new File(pkcs7.getParentFile, "empty.crl")
    openssl(sh"""ca -config "$CONF" -gencrl -keyfile "$CAKEY" -cert "$CACERT" -out "$crl"""")
    val certfiles = certs.reverse.map(f => sh"-certfile $f".toString).mkString(" ")
    openssl(sh"""crl2pkcs7 $certfiles -in "$crl" -out "$pkcs7"""")
    crl.delete()
  }

  def childCA(dir:File, subject:String, days:Int = 365):CA = {
    val ca = new CA(dir)
    ca.init(days)
    openssl(sh"""req -config "${ca.CONF}" -new -newkey ec:<(openssl ecparam -name prime256v1) -nodes -out "${ca.CAREQ}" -days $days -keyout "${ca.CAKEY}" -multivalue-rdn -subj "$subject"""")
    openssl(sh"""ca -config "$CONF" -batch -policy policy_match -extensions v3_ca -days $days -out "${ca.CACERT}" -infiles "${ca.CAREQ}"""")
    ca
  }

}

object CA {
  private[CA] val logger = LoggerFactory.getLogger(classOf[CA])

  private[CA] def copyPEM(src:File, dst:File, bound:String):Unit = using(new FileWriter(dst)) { out =>
    val begin = s"-----BEGIN.*$bound".r
    val end = s"-----END.*$bound".r
    out.write(Files.readAllLines(src.toPath, StandardCharsets.UTF_8).asScala
      .dropWhile(line => !begin.pattern.matcher(line).lookingAt())
      .reverse
      .dropWhile(line => !end.pattern.matcher(line).lookingAt()).reverse.mkString("\n"))
  }

}