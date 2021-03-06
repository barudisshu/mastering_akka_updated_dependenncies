package com.navneetgupta.bookstore.inventory

import akka.actor.ActorRef
import scala.concurrent.ExecutionContext
import com.navneetgupta.bookstore.common.BookstorePlan
import unfiltered.request.Params
import io.netty.channel.ChannelHandler.Sharable
import unfiltered.request._
import unfiltered.response._
import org.json4s._
import org.json4s.native.JsonMethods._

@Sharable
class InventoryEndpoint(inventoryClerk: ActorRef, bookView: ActorRef)(implicit override val ec: ExecutionContext) extends BookstorePlan {

  import akka.pattern._
  import com.navneetgupta.bookstore.inventory.InventoryClerk._
  import BookView._

  object TagParam extends Params.Extract("tag", { values =>
    val filtered = values.filter(_.nonEmpty)
    if (filtered.isEmpty) None else Some(filtered)
  })
  object AuthorParam extends Params.Extract("author", Params.first ~> Params.nonempty)

  override def intent = {
    case req @ GET(Path(Seg("api" :: "book" :: bookId :: Nil))) =>
      println("inventoryClerk Actor is {} " + inventoryClerk.getClass.getName + "         " + inventoryClerk.path.name)
      val f = (inventoryClerk ? FindBook(bookId))
      respond(f, req)
    case req @ GET(Path(Seg("api" :: "book" :: Nil))) & Params(TagParam(tags)) =>
      println("Tags to search is " + tags)
      println("bookView Actor is {} ", bookView.getClass.getName + "            " + bookView.path.name)
      val f = (bookView ? FindBooksByTags(tags))
      respond(f, req)
    case req @ GET(Path(Seg("api" :: "book" :: Nil))) & Params(AuthorParam(author)) =>
      val f = (bookView ? FindBooksByAuthor(author))
      respond(f, req)

    case req @ POST(Path(Seg("api" :: "book" :: Nil))) =>
      val createBook = parseJson[CreateBook](Body.string(req))
      val f = (inventoryClerk ? createBook)
      respond(f, req)

    case req @ Path(Seg("api" :: "book" :: bookId :: "tag" :: tag :: Nil)) =>
      req match {
        case PUT(_) =>
          respond((inventoryClerk ? AddTagToBook(bookId, tag)), req)
        case DELETE(_) =>
          respond((inventoryClerk ? RemoveTagFromBook(bookId, tag)), req)
        case other =>
          req.respond(Pass)
      }
    case req @ PUT(Path(Seg("api" :: "book" :: bookId :: "inventory" :: amount :: Nil))) =>
      val f = (inventoryClerk ? AddInventoryToBook(bookId, amount.toInt))
      respond(f, req)

    case req @ DELETE(Path(Seg("api" :: "book" :: bookId :: Nil))) =>
      val f = (inventoryClerk ? DeleteBook(bookId))
      respond(f, req)
  }
}
