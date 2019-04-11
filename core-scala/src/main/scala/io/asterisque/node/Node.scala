package io.asterisque.node

import java.io.File
import io.asterisque.carillon.using

object Node {

  def main(args:Array[String]):Unit = using(new Context(new File("apps/sample/"))) { context =>
    context.trustContext()
  }

}
