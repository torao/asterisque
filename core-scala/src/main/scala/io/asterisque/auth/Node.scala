package io.asterisque.auth

import java.security.cert.X509Certificate
import java.util.UUID

class Node(certificate:X509Certificate) extends Principal {

  val id:UUID = UUID.fromString(certificate.getSubjectX500Principal.getName)

  def verify(content:Array[Byte], signature:Array[Byte]):Boolean = ???
}
