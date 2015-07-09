package bubblewrap

import java.io.IOException
import java.util.concurrent.TimeoutException

import bubblewrap.TestUtils._
import com.ning.http.client.AsyncHandler.STATE
import com.ning.http.client.{FluentCaseInsensitiveStringsMap, HttpResponseBodyPart, HttpResponseHeaders, HttpResponseStatus}
import org.mockito.Mockito._
import org.scalatest.FlatSpec
import org.scalatest.Inside
import org.scalatest.Matchers.{be, convertToAnyShouldWrapper}

class HttpHandlerSpec extends FlatSpec with Inside{
  val config: CrawlConfig = CrawlConfig(None, "bubblewrap", 1000, Cookies.None)
  val url = WebUrl("http://www.example.com/1")

  def httpStatus(code:Int) = {
    val status = mock(classOf[HttpResponseStatus])
    when(status.getStatusCode).thenReturn(code)
    status
  }

  def httpHeaders(headers:(String, String)*) = {
    new HttpResponseHeaders {
      override def getHeaders: FluentCaseInsensitiveStringsMap = headers.foldLeft(new FluentCaseInsensitiveStringsMap())((m,t) =>m.add(t._1,t._2))
    }
  }

  def bodyPart(value:Array[Byte]) = {
    val part = mock(classOf[HttpResponseBodyPart])
    when(part.getBodyPartBytes).thenReturn(value)
    part
  }

  "HttpHandler" should "add parts to the body and fulfil body once complete" in {
    val handler = new HttpHandler(config, url)
    val response = handler.httpResponse.future
    handler.onStatusReceived(httpStatus(200))

    handler.onHeadersReceived(httpHeaders()) should be(STATE.CONTINUE)
    handler.onBodyPartReceived(bodyPart("Hello ".getBytes)) should be(STATE.CONTINUE)
    handler.onBodyPartReceived(bodyPart("World".getBytes())) should be(STATE.CONTINUE)

    handler.onCompleted()

    response.isCompleted should be(true)
    get(response).pageResponse match {
      case SuccessResponse(content) => content.asString should be("Hello World")
      case _ => fail("Did not produce expected success response")
    }
  }

  it should "abort in case the content-length header has larger size than in crawl config" in {
    val handler = new HttpHandler(config, url)
    val response = handler.httpResponse.future
    handler.onStatusReceived(httpStatus(200))

    handler.onHeadersReceived(httpHeaders("Content-Length" -> "1001")) should be(STATE.ABORT)
    get(response).pageResponse match {
      case FailureResponse(error) => error.getMessage should be("Exceeds allowed max size 1000")
      case _ => fail("Did not produce expected failure response")
    }
  }

  it should "continue if the content-length is well within max size" in {
    val handler = new HttpHandler(config, url)
    val response = handler.httpResponse.future
    handler.onStatusReceived(httpStatus(200))

    handler.onHeadersReceived(httpHeaders("Content-Length" -> "1000")) should be(STATE.CONTINUE)
    response.isCompleted should be(false)
  }

  it should "abort if the body content exceeds the max size" in {
    val handler = new HttpHandler(config, url)
    val response = handler.httpResponse.future
    handler.onStatusReceived(httpStatus(200))

    handler.onHeadersReceived(httpHeaders()) should be(STATE.CONTINUE)
    handler.onBodyPartReceived(bodyPart(("h"*500).getBytes)) should be(STATE.CONTINUE)
    handler.onBodyPartReceived(bodyPart(("h"*501).getBytes)) should be(STATE.ABORT)
    get(response).pageResponse match {
      case FailureResponse(error) => error.getMessage should be("Exceeds allowed max size 1000")
      case _ => fail("Did not produce expected failure response")
    }
  }

  it should "have location in the response in case of redirects" in {
    val handler = new HttpHandler(config, url)
    val response = handler.httpResponse.future
    handler.onStatusReceived(httpStatus(302))

    handler.onHeadersReceived(httpHeaders("Location" -> "/home")) should be(STATE.CONTINUE)
    handler.onCompleted()

    get(response).redirectLocation should be(Some("/home"))
  }

  it should "use the content-type header to parse the body part" in {
    val koreanString = "동서게임 MS정품유선컨트롤러/PCGTA5호환 - 11번가"
    val handler = new HttpHandler(config, url)
    val response = handler.httpResponse.future
    handler.onStatusReceived(httpStatus(200))

    handler.onHeadersReceived(httpHeaders("Content-Type" -> "text/html; charset=euc-kr"))
    handler.onBodyPartReceived(bodyPart(koreanString.getBytes("euc-kr")))
    handler.onCompleted()

    get(response).pageResponse match {
      case SuccessResponse(content) => content.asString should be(koreanString)
      case _ => fail("Did not produce expected success response")
    }
  }

  it should "use timeout error code when reporting connection and read timeouts" in {
    val handler = new HttpHandler(config, url)
    val response = handler.httpResponse.future

    handler.onThrowable(new TimeoutException("read timeout"))
    get(response).status should be(9999)
  }


  it should "use unknown error code when reporting exceptiosn which arent timeouts" in {
    val handler = new HttpHandler(config, url)
    val response = handler.httpResponse.future

    handler.onThrowable(new RuntimeException("I dont know what went wrong, really")) 
    get(response).status should be(1006)
  }


  it should "use remotely closed error code when reporting ioexception with remotely closed as message" in {
    val handler = new HttpHandler(config, url)
    val response = handler.httpResponse.future

    handler.onThrowable(new IOException("Remotely closed"))
    get(response).status should be(9998)
  }
}
