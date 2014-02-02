Asterisk\*
========

Asterisk\* is lightweight bidirectional RPC with asynchronous messaging framework, protocol and implementation.

Documents
=========

* [Motive](http://prezi.com/ia6rjvjrhe6d/asterisk-motivation/)
* [Brain Storming](http://prezi.com/ktjdnfshx8dv/asterisk-brain-storming/)
* [Introduction](docs/introduction.md)

Getting Started
===============

To build asterisk\* JAR library, you may clone asterisk\* GitHub repository and build `asterisk_2.10-0.1.jar` by
`sbt package`.

```sh
$ git clone https://github.com/torao/asterisk.git
$ cd asterisk
$ ./sbt package
```

Or, you can also run following sample code by `sbt run` without build.

```scala
import com.kazzla.asterisk._
import java.net.InetSocketAddress
import scala.concurrent.{Await,Future,Promise}
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util._
// 1) Define scala trait or java interface as IDL those every methods has @Export(function-id) and Future return type
//    to agree interface statically between client and server.
trait Sample {
  @Export(10)
  def greeting(name:String):Future[String]
}
// 2) Implementation class is used as remote service on server node.
class SampleImpl extends Service with Sample {
  def greeting(name:String) = Promise.successful(s"hello, $name").future
}
object SampleImpl {
  // 3) Instantiate client and server nodes that use Netty as messenger bridge.
  val server = Node("sample server").serve(new SampleImpl).bridge(netty.Netty).build()
  val client = Node("sample client").bridge(netty.Netty).build()
  def close() = Seq(server,client).foreach{ _.shutdown() }
  def main(args:Array[String]):Unit = {
    // 4) The server listen on port 9999 without any action in accept.
    server.listen(new InetSocketAddress(9999)){ _ => None }
    // 5) Client retrieve `Future[Session]` by connecting to server port 9999.
    val future = client.connect(new InetSocketAddress(9999))
    val session = Await.result(future, Duration.Inf)
    // 6) Bind remote interfaces from client session.
    val sample = session.bind(classOf[Sample])
    // 7) Call remote procedure and action asynchronously.
    sample.greeting("asterisk").onComplete {
      case Success(str) =>
        println(str)
        close()
      case Failure(ex) =>
        ex.printStackTrace()
        close()
    }
  }
}
```

```sh
$ ./sbt run
[info] Set current project to asterisk (in build file:/Users/torao/git/asterisk/)
[info] Compiling 1 Scala source to /Users/torao/git/asterisk/target/scala-2.10/classes...
[info] Running SampleImpl
hello, asterisk
[success] Total time: 11 s, completed 2014/02/03 5:53:31
```

License
=======
All sources and related resources are available [Apache License Version 2.0](LICENSE).