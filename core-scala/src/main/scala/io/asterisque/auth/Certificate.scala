package io.asterisque.auth

import java.security.cert.X509Certificate

/**
  * x.509 certificate with attributes to be used in asterisque. This body is represented in binary and signed by
  * the issuer.
  *
  * @param binary      certificate binary
  * @param siType      signature type
  * @param certificate X.509 certificate
  * @param attr        certificate attribute
  */
case class Certificate(cert:X509Certificate, attrs:Map[String, String]) {
}