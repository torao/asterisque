/*
 * Copyright (c) 2014 koiroha.org.
 * All sources and related resources are available under Apache License 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0.html
*/
package sample

import com.kazzla.asterisk._
import java.net.InetSocketAddress
import scala.concurrent.{Future, Promise, Await}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Success
import scala.util.Failure
import scala.concurrent.duration.Duration
import scala.io.Source
import scala.annotation.tailrec

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Sample5
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 *
 * @author Takami Torao
 */
object Sample5 {

	def ping(sec:Int)(pipe:Pipe):Future[Any] = {
		pipe.src.foreach{ b => println(b.getString) }
		scala.concurrent.future {
			(0 to sec).foreach{ s =>
				pipe.sink.send(s.toString.getBytes)
				Thread.sleep(1000)
			}
			sec
		}
	}

  class PingService extends Service {
	  @Export(10)
	  def p(sec:Int) = withPipe(ping(sec))
  }

  def main(args:Array[String]):Unit = {

    // Server node serves greeting service on port 5330 without any action on accept connection.
    val server = Node("server").serve(new PingService()).build()
    server.listen(new InetSocketAddress("localhost", 5330), None)

	  // Client node connect to server.
    val client = Node("client").build()
    client.connect(new InetSocketAddress("localhost", 5330), None).onComplete{
      case Success(session) =>
	      val future = session.open(10, 10)(ping(10))
	      System.out.println(Await.result(future, Duration.Inf))
        server.shutdown()
        client.shutdown()
      case Failure(ex) => ex.printStackTrace()
    }
  }

}
