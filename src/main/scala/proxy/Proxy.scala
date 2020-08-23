package proxy

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, MediaTypes, StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Directives.{complete, get, path}
import akka.http.scaladsl.server.Route

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class Proxy()(implicit system: ActorSystem[Nothing], ec: ExecutionContext) {

  import Proxy._

  val route: Route =
    path(Remaining) { path =>
      get {
        complete(handle(path))
      }
    }

  private def handle(path: String): Future[HttpResponse] = {
    def validateResponse(response: HttpResponse) =
      if (response.status == StatusCodes.OK)
        Future.successful(response)
      else
        Future.failed(new Exception(s"Invalid response status: $response.status"))

    def isJavascript(response: HttpResponse) =
      response.entity.contentType.mediaType == MediaTypes.`application/javascript`

    def fixJavascript(response: HttpResponse) =
      response.entity.toStrict(DownloadTimeout).map { entity =>
        val original = entity.data.utf8String
        val fixed =
          Location +
            original
              .replaceAll("window.location", "_\\$location")
              .replaceAll("document.location", "_\\$location")

        response.withEntity(fixed)
      }


    val uri = Uri(s"//$path").withScheme("http")

    println("Request: " + uri)

    for {
      response <- Http().singleRequest(HttpRequest(uri = uri))
      _        <- validateResponse(response)
      result   <- if (isJavascript(response)) fixJavascript(response) else Future.successful(response)
    } yield result
  }
}

object Proxy {
  val DownloadTimeout: FiniteDuration = 30.seconds

  val Location: String =
    """
      |const _$location: Location = {
      |    host: '',
      |    protocol: '',
      |    hash: '',
      |    href: '',
      |    hostname: '',
      |    origin: '',
      |    pathname: '',
      |    port: '',
      |    search: '',
      |    ancestorOrigins: null,
      |    assign: null,
      |    reload: null,
      |    replace: null,
      |};
      |
      |""".stripMargin
}