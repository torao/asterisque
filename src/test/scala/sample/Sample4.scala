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
// Sample4
// ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
/**
 *
 * @author Takami Torao
 */
object Sample4 {

	def send(charset:String)(pipe:Pipe):Future[Any] = {
		scala.concurrent.future {
			pipe.out.write("日々是良日".getBytes(charset))
			pipe.out.close()
		}
		pipe.future
	}

	def receive(charset:String)(pipe:Pipe):Future[Any] = {
		// You MUST call useInputStream() to use pipe.in in function caller thread.
		pipe.useInputStream()
		scala.concurrent.future {
			new String(Source.fromInputStream(pipe.in, charset).buffered.toArray)
		}
	}

  /**
   * Service implementation specify `Service` and interface.
   */
  class StreamService extends Service {
	  // encode stream binary to string by specified charset
	  @Export(value=10)
	  def makeString(charset:String) = withPipe(receive(charset))
	  // encode stream binary to string by specified charset
	  @Export(value=20)
	  def sendString(charset:String) = withPipe(send(charset))

	  // echo stream binary and return byte length as result
    30 accept { args =>
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
    server.listen(new InetSocketAddress("localhost", 5334), None)

	  // Client node connect to server.
    val client = Node("client").build()
    client.connect(new InetSocketAddress("localhost", 5334), None).onComplete{
      case Success(session) =>
        // NOTE: Interface binding is not supported in messaging client.
	      val f1 = session.open(10, "UTF-8")(send("UTF-8"))
	      System.out.println(Await.result(f1, Duration.Inf))  // 日々是良日

	      // Processing is interchangeable regardless of whether either is opened.
        val f2 = session.open(20, "UTF-8")(receive("UTF-8"))
	      System.out.println(Await.result(f2, Duration.Inf))  // 日々是良日

	      {
		      val send = Promise[Int]()
		      val receive = Promise[Int]()
		      val f3 = session.open(30){ pipe =>
			      pipe.useInputStream()
			      scala.concurrent.future {
				      var count = 0
				      while(pipe.in.read() >= 0) count += 1
				      receive.success(count)
			      }
			      scala.concurrent.future {
				      val size = util.Random.nextInt(256 * 1024)
				      (0 until size).foreach{ _ => pipe.out.write(0x00) }
				      pipe.out.close()    // flush buffer and send eof
				      send.success(size)
			      }
		        pipe.future
		      }
		      val receiveCount = Await.result(receive.future, Duration.Inf)
		      val sendCount = Await.result(send.future, Duration.Inf)
		      val serverCount = Await.result(f3, Duration.Inf)
		      System.out.println(s"send=$sendCount, receive=$receiveCount, server-detected=$serverCount")
	      }
        server.shutdown()
        client.shutdown()
      case Failure(ex) => ex.printStackTrace()
    }
  }

}
