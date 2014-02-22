/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package sample

import com.kazzla.asterisk.{Node, Service, Export}
import java.net.InetSocketAddress
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Failure}

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Sample1
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 * Sample implementation that declare and shrare interface between client and server.. This method
 * is typically useful in compatibility to static typing language such as java or scala.
 *
 * @author Takami Torao
 */
object Sample1 {

  /**
   * Interface that defines remote invokable functions and is shared client and server. All of
   * methods must be declared with `@Export` annotation.
   */
  trait GreetingService {

    /**
     * All exported method must have `@Export` annotation that specify unique function identifier
     * on a service, and `Future` return type even if its result will be cause in invocation thread
     * synchronously.
     *
     * @param name your name
     * @return greeting message
     */
    @Export(10)
    def greeting(name:String):Future[String]

    @Export(20)
    def lazyGreeting(name:String):Future[String]
  }

  /**
   * Service implementation specify `Service` and interface.
   */
  class GreetingServiceImpl extends Service with GreetingService {
    def greeting(name:String):Future[String] = Future(s"hello, $name")
    def lazyGreeting(name:String):Future[String] = scala.concurrent.future {
      Thread.sleep(3 * 1000)
      s"hello, $name"
    }
  }

  def main(args:Array[String]):Unit = {

    // Server node serves greeting service on port 5330 without any action on accept connection.
    val server = Node("server").serve(new GreetingServiceImpl()).build()
    server.listen(new InetSocketAddress("localhost", 5330), None)

    // Client node connect to server.
    val client = Node("client").build()
    client.connect(new InetSocketAddress("localhost", 5330), None).onComplete{
      case Success(session) =>
        // Bind known service interface from session, and call greeting service
        // asynchronously.
        val service = session.bind(classOf[GreetingService])
        service.greeting("asterisque").andThen {
          case Success(result) => System.out.println(result)
          case Failure(ex) => ex.printStackTrace()
        }.onComplete { _ =>
          // You will shutdown client and server if all of your requirement is finish.
          server.shutdown()
          client.shutdown()
        }
      case Failure(ex) => ex.printStackTrace()
    }
  }

}
