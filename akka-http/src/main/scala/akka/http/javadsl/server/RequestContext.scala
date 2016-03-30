package akka.http.javadsl.server

import akka.http.javadsl.model.HttpRequest
import scala.concurrent.ExecutionContextExecutor
import akka.stream.Materializer
import akka.event.LoggingAdapter
import akka.http.javadsl.settings.RoutingSettings
import akka.http.javadsl.settings.ParserSettings
import akka.http.javadsl.model.HttpResponse
import java.util.concurrent.CompletionStage
import java.util.function.{Function => JFunction}
import akka.http.scaladsl
import akka.http.javadsl.server.JavaScalaTypeEquivalence._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import scala.compat.java8.FutureConverters._
import scala.annotation.varargs
import akka.http.scaladsl.model.Uri.Path

class RequestContext private (delegate: scaladsl.server.RequestContext) {
  import RequestContext._
  import scala.concurrent.ExecutionContext.Implicits.global // only for type-converting future.map
  
  def getRequest(): HttpRequest = delegate.request
  def getUnmatchedPath(): String = delegate.unmatchedPath.toString()
  def getExecutionContext(): ExecutionContextExecutor = delegate.executionContext
  def getMaterializer(): Materializer = delegate.materializer
  def getLog(): LoggingAdapter = delegate.log
  def getSettings(): RoutingSettings = delegate.settings
  def getParserSettings(): ParserSettings = delegate.parserSettings
  
  def reconfigure(
    executionContext: ExecutionContextExecutor,
    materializer: Materializer,
    log: LoggingAdapter,
    settings: RoutingSettings): RequestContext = wrap(delegate.reconfigure(executionContext, materializer, log, settings))
  
  def complete[T](value: T, marshaller: Marshaller[T, HttpResponse]): CompletionStage[RouteResult] = {
    import marshaller.asScala
    delegate.complete(ToResponseMarshallable(value)(marshaller.asScala)).map(r => r:RouteResult).toJava
  }
  
  @varargs def reject(rejections: Rejection*): CompletionStage[RouteResult] = {
    val scalaRejections = rejections.map(r => r:scaladsl.server.Rejection)
    delegate.reject(scalaRejections: _*).map(r => r:RouteResult).toJava
  }
  
  def fail(error: Throwable): CompletionStage[RouteResult] = delegate.fail(error).map(r => r:RouteResult).toJava
  def withRequest(req: HttpRequest): RequestContext = wrap(delegate.withRequest(req))
  def withExecutionContext(ec: ExecutionContextExecutor): RequestContext = wrap(delegate.withExecutionContext(ec))
  def withMaterializer(materializer: Materializer): RequestContext = wrap(delegate.withMaterializer(materializer))
  def withLog(log: LoggingAdapter): RequestContext = wrap(delegate.withLog(log))
  def withRoutingSettings(settings: RoutingSettings): RequestContext = wrap(delegate.withRoutingSettings(settings))
  def withParserSettings(settings: ParserSettings): RequestContext = wrap(delegate.withParserSettings(settings))
  
  def mapRequest(f: JFunction[HttpRequest, HttpRequest]): RequestContext = wrap(delegate.mapRequest(r => f.apply(r)))
  def withUnmatchedPath(path: String): RequestContext = wrap(delegate.withUnmatchedPath(Path(path)))
  def mapUnmatchedPath(f: JFunction[String, String]): RequestContext = wrap(delegate.mapUnmatchedPath(p => Path(f.apply(p.toString()))))
  def withAcceptAll: RequestContext = wrap(delegate.withAcceptAll)
}

object RequestContext {
  /** INTERNAL API */
  private[server] def wrap(delegate: scaladsl.server.RequestContext) = new RequestContext(delegate)
}