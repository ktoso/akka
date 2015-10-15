package akka.http.scaladsl.server

import akka.stream.stage.{ SyncDirective, Context, PushPullStage }
import akka.util.ByteString

import scala.util.{ Failure, Success }

class JsonCollectingStage extends PushPullStage[ByteString, ByteString] {
  private val buffer = new JsonCollectingBuffer

  override def onPush(elem: ByteString, ctx: Context[ByteString]): SyncDirective = {
    buffer.append(elem)
    popBuffer(ctx)
  }

  override def onPull(ctx: Context[ByteString]): SyncDirective =
    popBuffer(ctx)

  def popBuffer(ctx: Context[ByteString]): SyncDirective =
    buffer.pop match {
      case Success(Some(value)) ⇒
        ctx.push(value)
      case Success(None) ⇒
        ctx.pull()
      case Failure(e) ⇒
        ctx.fail(e)
    }
}