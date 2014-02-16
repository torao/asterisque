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
import scala.io.Source
import scala.annotation.tailrec

// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
// Sample4
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 *
 * @author Takami Torao
 */
object Sample4 {
  /**
   * Service implementation specify `Service` and interface.
   */
  class StreamService extends Service {
	  // encode stream binary to string by specified charset
	  @Export(value=10)
    def makeString(charset:String) = withPipe { pipe =>
      // You MUST call useInputStream() to use pipe.in in function caller thread.
	    pipe.useInputStream()
	    scala.concurrent.future {
		    new String(Source.fromInputStream(pipe.in, charset).buffered.toArray)
	    }
    }
	  // echo stream binary and return byte length as result
    20 accept { args =>
      withPipe { pipe =>
	      // You MUST call useInputStream() to use pipe.in in function caller thread.
	      pipe.useInputStream()
        scala.concurrent.future {
	        @tailrec
	        def readAndWrite(length:Long, buffer:Array[Byte]):Long = {
		        val len = pipe.in.read(buffer)
		        if(len > 0){
			        pipe.out.write(buffer, 0, len)
			        readAndWrite(length + len, buffer)
		        } else {
			        length
		        }
	        }
	        readAndWrite(0, new Array[Byte](1024))
        }
      }
    }
  }

  def main(args:Array[String]):Unit = {

    // Server node serves greeting service on port 5330 without any action on accept connection.
    val server = Node("server").serve(new StreamService()).build()
    server.listen(new InetSocketAddress("localhost", 5330), None)

	  // Client node connect to server.
    val client = Node("client").build()
    client.connect(new InetSocketAddress("localhost", 5330), None).onComplete{
      case Success(session) =>
        // NOTE: Interface binding is not supported in messaging client.
	      val p1 = session.open(10, "UTF-8")
	      p1.out.write("日々是良日".getBytes("UTF-8"))
	      p1.out.close()
	      System.out.println(Await.result(p1.future, Duration.Inf))  // 日々是良日

	      val p2 = session.open(20){ _.useInputStream() }
	      val size = util.Random.nextInt(1024 * 1024)
	      val receive = scala.concurrent.future {
					var count = 0
					while(p2.in.read() >= 0) count += 1
		      count
	      }
	      val send = scala.concurrent.future {
		      (0 until size).foreach{ _ => p2.out.write(0x00) }
		      p2.out.close()    // flush buffer and send eof
		      size
	      }
	      val receiveCount = Await.result(receive, Duration.Inf)
	      val sendCount = Await.result(send, Duration.Inf)
	      val serverCount = Await.result(p2.future, Duration.Inf)
	      System.out.println(s"send=$sendCount, receive=$receiveCount, server-detected=$serverCount")
        server.shutdown()
        client.shutdown()
      case Failure(ex) => ex.printStackTrace()
    }
  }

}
