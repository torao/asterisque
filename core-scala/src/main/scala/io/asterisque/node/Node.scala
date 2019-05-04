package io.asterisque.node

import java.io.File
import java.net.URI

import io.asterisque.utils.using
import io.asterisque.wire.gateway.Bridge
import io.asterisque.wire.rpc.{Codec, Dispatcher, MessagePackCodec}

import scala.concurrent.{ExecutionContext, Future}

class Node(threads:ExecutionContext, codec:Codec = MessagePackCodec) {
  val dispatcher:Dispatcher = new Dispatcher(threads, codec)

//  def connect(uri:URI):Future[_] = {
//    implicit val _ctx:ExecutionContext = threads
//    Bridge.builder().newWire(uri).
//  }

}

object Node {

//  def main(args:Array[String]):Unit = using(new Context(new File("apps/sample/"))) { context =>
//    context.trustContext()
//  }

}
