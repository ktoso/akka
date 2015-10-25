package akka.http.scaladsl.server

import akka.http.scaladsl.model.headers.{ GenericHttpCredentials, Authorization }
import akka.http.scaladsl.server.ClientDemo._

trait DemoClientAuthSettings {

  lazy val data =
    """
      |oauth_consumer_key="32RLV3a77ECWrE32SG5Pg0NkB", oauth_nonce="954cbfd916070ff08e654171f4f99449", oauth_signature="GjaKhbCCFG9okksGN3luFyopvhg%3D", oauth_signature_method="HMAC-SHA1", oauth_timestamp="1445893666", oauth_token="54490597-mpxeU54vhTY6Wl1gbzGtj8kTgGR1TWMhKpxbLcXFb", oauth_version="1.0"
      |"""".stripMargin.replaceAll(" ", "").replaceAll("\"", "").replaceAll("\n", "")

  def oAuthParams = Map {
    data.split(",").toList.map { kv ⇒
      val ps = kv.split("=").toList
      ps.head -> ps.tail.head
    }(collection.breakOut): _*
  }

  def twitterAuthorization: Authorization = {
    val creds = GenericHttpCredentials("OAuth", oAuthParams)
    println("oAuthParams = " + oAuthParams)
    Authorization(creds)
  }
}
