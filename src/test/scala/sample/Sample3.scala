/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package sample

import com.kazzla.asterisk._
import java.net.InetSocketAddress
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
  trait StreamService {
    @Export(value=10,stream=true)
    def sum():Future[Int]
  }

  /**
   * Service implementation specify `Service` and interface.
   */
  class StreamServiceImpl extends Service with StreamService {
    def sum() = withPipe { pipe =>
      pipe.blocks.map{ block => block.toByteBuffer.getInt }.sum
    }

    20 accept { args =>
      withPipe { pipe =>
        var count = 0
        pipe.blocks.foreach { block =>
          System.out.println(s"C>>S ${new String(block.payload, block.offset, block.length)}")
          count += 1
        }.collect{ case _ => count }
      }
    } stream true
  }

  def main(args:Array[String]):Unit = {

    // Server node serves greeting service on port 5330 without any action on accept connection.
    val server = Node("server").serve(new StreamServiceImpl()).build()
    server.listen(new InetSocketAddress("localhost", 5330), None)

    // Client node connect to server.
    val client = Node("client").build()
    client.connect(new InetSocketAddress("localhost", 5330), None).onComplete{
      case Success(session) =>
        // Bind known service interface from session, and call greeting service
        // asynchronously.
        val pipe = session.open(20)
        (1 to 10).foreach { i => pipe.blocks << i.toString.getBytes }
        pipe.blocks.sendEOF()
        val count = Await.result(pipe.future, Duration.Inf)
        System.out.println(s"Total $count available blocks.")
        // NOTE: There is no way to receive block if you use interface binding though you can receive result.
        //       No response returns because server expects EOF.
        try {
          session.bind(classOf[StreamService]).sum().onComplete{
            case Success(c) => System.out.println(s"Total $c available blocks.")
            case Failure(e) => e.printStackTrace()
          }
        } catch {
          case ex:UnsupportedOperationException => None
        }
        // You should specify block receive handler
        session.openWithStream(10, 10).blocks.foreach { b =>
          if(!b.isEOF){
            System.out.println(s"C<<S ${new String(b.payload,b.offset,b.length)}")
          }
        }.onComplete { _ =>
        // You will shutdown client and server if all of your requirement is finish.
          server.shutdown()
          client.shutdown()
        }
      case Failure(ex) => ex.printStackTrace()
    }
  }

}
