/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package sample

import com.kazzla.asterisk._
import java.net.InetSocketAddress

import io.asterisque.Export

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Success
import scala.util.Failure
import scala.concurrent.duration.Duration

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Sample3
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 *
 * @author Takami Torao
 */
object Sample3 {
  /**
   * Service implementation specify `Service` and interface.
   */
  class MessageService extends Service(global) {
    @Export(value=10)
    def sum():Future[Int] = withPipe { pipe =>
      pipe.src.filterNot{ _.isEOF }.map{ _.toByteBuffer.getString("UTF-8").toInt }.sum
    }
    20 accept { args =>
      withPipe { pipe =>
        pipe.src.filterNot{ _.isEOF }.map{ b =>
          pipe.sink.send(b.payload, b.offset, b.length)
        }.count(_=>true)
      }
    }
  }

  def main(args:Array[String]):Unit = {

    // Server node serves greeting service on port 5330 without any action on accept connection.
    val server = Node("server").serve(new MessageService()).build()
    server.listen(new InetSocketAddress("localhost", 5333), None)

    // send "1","2","3","4" to pipe and print received block as string
    def receive(pipe:Pipe):Future[Any] = {
      pipe.src.foreach{ b => println(b.getString) }
      Seq(1, 2, 3, 4).map{ n => n.toString.getBytes }.foreach{ b => pipe.sink.send(b) }
      pipe.sink.sendEOF()
      pipe.future
    }

    // Client node connect to server.
    val client = Node("client").build()
    client.connect(new InetSocketAddress("localhost", 5333), None).onComplete{
      case Success(session) =>
        // NOTE: Interface binding is not supported in messaging client.
        // Bind known service interface from session, and call greeting service
        // asynchronously.
        val f1 = session.open(10)(receive)
        val f2 = session.open(20)(receive)
        val sum = Await.result(f1, Duration.Inf)
        val count = Await.result(f2, Duration.Inf)
        System.out.println(s"sum=$sum, count=$count")
        server.shutdown()
        client.shutdown()
      case Failure(ex) => ex.printStackTrace()
    }
  }

}
