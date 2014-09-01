/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http

import akka.actor.ActorSystem
import akka.http.model._
import akka.stream.testkit.AkkaSpec
import akka.util.Timeout

import scala.concurrent.duration._

class HttpServerExampleSpec
  extends AkkaSpec("akka.actor.default-mailbox.mailbox-type = akka.dispatch.UnboundedMailbox") {
  def ActorSystem(): ActorSystem = system

  "binding example" in {
    //#bind-example
    import akka.http.Http
    import akka.io.IO
    import akka.pattern.ask
    import akka.stream.FlowMaterializer
    import akka.stream.scaladsl.Flow

    implicit val system = ActorSystem()
    import system.dispatcher
    implicit val materializer = FlowMaterializer()
    implicit val askTimeout: Timeout = 500.millis

    val bindingFuture = IO(Http) ? Http.Bind(interface = "localhost", port = 8080)
    bindingFuture foreach {
      case Http.ServerBinding(localAddress, connectionStream) ⇒
        Flow(connectionStream).foreach({
          case Http.IncomingConnection(remoteAddress, requestProducer, responseConsumer) ⇒
            println("Accepted new connection from " + remoteAddress)

          // handle connection here
        })
    }
    //#bind-example
  }
  "full-server-example" in {
    import akka.http.Http
    import akka.io.IO
    import akka.pattern.ask
    import akka.stream.FlowMaterializer
    import akka.stream.scaladsl.Flow

    implicit val system = ActorSystem()
    import system.dispatcher
    implicit val materializer = FlowMaterializer()
    implicit val askTimeout: Timeout = 500.millis

    val bindingFuture = IO(Http) ? Http.Bind(interface = "localhost", port = 8080)

    //#full-server-example
    import akka.http.model.HttpMethods._

    val requestHandler: HttpRequest ⇒ HttpResponse = {
      case HttpRequest(GET, Uri.Path("/"), _, _, _) ⇒
        HttpResponse(
          entity = HttpEntity(MediaTypes.`text/html`,
            "<html><body>Hello world!</body></html>"))

      case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  ⇒ HttpResponse(entity = "PONG!")
      case HttpRequest(GET, Uri.Path("/crash"), _, _, _) ⇒ sys.error("BOOM!")
      case _: HttpRequest                                ⇒ HttpResponse(404, entity = "Unknown resource!")
    }

    // ...
    bindingFuture foreach {
      case Http.ServerBinding(localAddress, connectionStream) ⇒
        Flow(connectionStream).foreach({
          case Http.IncomingConnection(remoteAddress, requestProducer, responseConsumer) ⇒
            println("Accepted new connection from " + remoteAddress)

            Flow(requestProducer).map(requestHandler).produceTo(responseConsumer)
        })
    }
    //#full-server-example
  }
}
