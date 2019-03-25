package io.asterisque.auth


class User(certificate:Certificate) extends Principal {
  def verify(content:Array[Byte], signature:Array[Byte]):Boolean = ???
}
