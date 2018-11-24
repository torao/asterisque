/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package sample

import com.kazzla.asterisk.{Node, Service}
import java.net.InetSocketAddress
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Failure}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Sample2
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * Sample implementation for DSL-based service.
 *
 * @author Takami Torao
 */
object Sample2 {

  /**
   * Service implementation specify `Service` and interface.
   */
  class GreetingServiceImpl extends Service(global) {
    10 accept { args => Future(s"hello, ${args(0)}") }
    20 accept { args =>
      scala.concurrent.future {
        Thread.sleep(3 * 1000)
        s"hello, ${args(0)}"
      }
    }
  }

  def main(args:Array[String]):Unit = {

    // Server node serves greeting service on port 5330 without any action on accept connection.
    val server = Node("server").serve(new GreetingServiceImpl()).build()
    server.listen(new InetSocketAddress("localhost", 5332), None)

    // Client node connect to server.
    val client = Node("client").build()
    client.connect(new InetSocketAddress("localhost", 5332), None).onComplete{
      case Success(session) =>
        // Bind known service interface from session, and call greeting service
        // asynchronously.
        session.open(20, "asterisque").onSuccess{
          case result =>
            System.out.println(result.toString)
            server.shutdown()
            client.shutdown()
        }
      case Failure(ex) => ex.printStackTrace()
    }
  }

}
