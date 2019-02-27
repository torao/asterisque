package io.asterisque.auth

trait Principal {

  def verify(content:Array[Byte], signature:Array[Byte]):Boolean
}
