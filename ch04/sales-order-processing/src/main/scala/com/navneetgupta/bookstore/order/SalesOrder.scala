package com.navneetgupta.bookstore.order

import java.util.Date

import com.navneetgupta.bookstore.common.{ EntityActor, EntityFieldsObject }
import akka.actor.Props
import com.navneetgupta.bookstore.common.ErrorMessage
import akka.actor.ActorRef
import com.navneetgupta.bookstore.credit.CreditCardInfo
import akka.actor.FSM
import com.navneetgupta.bookstore.users.UserFO
import com.navneetgupta.bookstore.inventory.BookFO
import scala.concurrent.duration._
import com.navneetgupta.bookstore.common.EntityRepository
import com.navneetgupta.bookstore.common.Failure
import com.navneetgupta.bookstore.common.FailureType
import com.navneetgupta.bookstore.common.ServiceResult
import com.navneetgupta.bookstore.inventory.InventoryClerk
import akka.actor.Identify
import com.navneetgupta.bookstore.users.CustomerRelationsManager
import com.navneetgupta.bookstore.credit.CreditAssociate
import akka.actor.ActorIdentity
import com.navneetgupta.bookstore.common.FullResult
import com.navneetgupta.bookstore.common.EmptyResult
import com.navneetgupta.bookstore.credit.CreditCardTransactionFO
import com.navneetgupta.bookstore.credit.CreditTransactionStatus
import com.navneetgupta.bookstore.common.PersistentEntity
import com.navneetgupta.bookstore.common.EntityEvent
import com.navneetgupta.bookstore.order.Datamodel._
import com.navneetgupta.bookstore.common.DatamodelReader

object SalesOrderStatus extends Enumeration {
  val InProgress, Approved, BackOrdered, Cancelled = Value
}
case class SalesOrderLineItemFO(lineItemNumber: Int, bookId: String, quantity: Int, cost: Double)

object SalesOrderFO {
  def empty = new SalesOrderFO("", "", "", SalesOrderStatus.InProgress, 0.0, Nil, new Date(0), new Date(0))
}
case class SalesOrderFO(override val id: String, userId: String, creditTxnId: String, status: SalesOrderStatus.Value, totalCost: Double, lineItems: List[SalesOrderLineItemFO], createTs: Date, modifyTs: Date, override val deleted: Boolean = false) extends EntityFieldsObject[String, SalesOrderFO] {
  override def assignId(id: String) = this.copy(id = id)
  override def markDeleted = this.copy(deleted = true)
}

object SalesOrder {
  import collection.JavaConversions._

  def props(id: String) = Props(classOf[SalesOrder], id)

  case class LineItemRequest(bookId: String, quantity: Int)

  object Command {
    case class CreateOrder(newOrderId: String, userEmail: String, lineItems: List[LineItemRequest], cardInfo: CreditCardInfo)
    case class CreateValidatedOrder(order: SalesOrderFO)
    case class UpdateOrderStatus(status: SalesOrderStatus.Value)
  }

  object Event {
    case class OrderCreated(order: SalesOrderFO) extends EntityEvent {
      def toDatamodel = {
        val lineItemsDm = order.lineItems.map { item =>
          Datamodel.SalesOrderLineItem.newBuilder().
            setBookId(item.bookId).
            setCost(item.cost).
            setQuantity(item.quantity).
            setLineItemNumber(item.lineItemNumber)
            .build
        }
        val orderDm = Datamodel.SalesOrder.newBuilder().
          setId(order.id).
          setCreateTs(order.createTs.getTime).
          setStatus(order.status.toString).
          addAllLineItem(lineItemsDm).
          setUserId(order.userId).
          setCreditTxnId(order.creditTxnId).
          setTotalCost(order.totalCost).
          build

        Datamodel.OrderCreated.newBuilder().
          setOrder(orderDm).
          build
      }
    }
    object OrderCreated extends DatamodelReader {
      def fromDatamodel = {
        case doc: Datamodel.OrderCreated =>
          val dmo = doc.getOrder()
          val items = dmo.getLineItemList().map { item =>
            SalesOrderLineItemFO(item.getLineItemNumber(), item.getBookId(), item.getQuantity(), item.getCost())
          }
          val order = SalesOrderFO(dmo.getId(), dmo.getUserId(), dmo.getCreditTxnId(),
            SalesOrderStatus.withName(dmo.getStatus()), dmo.getTotalCost(), items.toList, new Date(dmo.getCreateTs()), new Date(dmo.getCreateTs()))
          OrderCreated(order)
      }
    }
    case class OrderStatusUpdated(status: SalesOrderStatus.Value) extends EntityEvent {
      def toDatamodel = Datamodel.OrderStatusUpdated.newBuilder().
        setStatus(status.toString).
        build
    }
    object OrderStatusUpdated extends DatamodelReader {
      def fromDatamodel = {
        case dm: Datamodel.OrderStatusUpdated =>
          OrderStatusUpdated(SalesOrderStatus.withName(dm.getStatus()))
      }
    }
  }
}
class SalesOrder(idInput: String) extends PersistentEntity[SalesOrderFO](idInput) {
  import context.dispatcher
  import SalesOrder._
  import EntityActor._

  override def snapshotAfterCount = Some(5)

  def initialState: SalesOrderFO = SalesOrderFO.empty

  override def additionalCommandHandling: Receive = {
    case co: Command.CreateOrder =>
      val validator = context.actorOf(SalesOrderCreateValidator.props)
      validator.forward(co)
    case Command.CreateValidatedOrder(order) =>
      //Now we can persist the complete order
      persist(Event.OrderCreated(order)) { event =>
        handleEventAndRespond()(event)

        //For now, still using akka event bus.  Will fix this in chapter 5
        val items = state.lineItems.map(i => (i.bookId, i.quantity))
        val invevent = InventoryClerk.OrderCreated(state.id, items)
        context.system.eventStream.publish(invevent)
      }

    case Command.UpdateOrderStatus(status) =>
      persist(Event.OrderStatusUpdated(status))(handleEvent)
  }

  def handleEvent(event: EntityEvent): Unit = event match {
    case Event.OrderCreated(order) =>
      state = order
    case Event.OrderStatusUpdated(status) =>
      state = state.copy(status = status)
  }

  def isCreateMessage(cmd: Any): Boolean = cmd match {
    case co: Command.CreateOrder           => true
    case cvo: Command.CreateValidatedOrder => true
    case _                                 => false
  }

  //  override def customCreateHandling: StateFunction = {
  //    case Event(req: CreateOrder, _) =>
  //      lookup(InventoryClerk.Name) ! Identify(ResolutionIdent.Book)
  //      lookup(CustomerRelationsManager.Name) ! Identify(ResolutionIdent.User)
  //      lookup(CreditAssociate.Name) ! Identify(ResolutionIdent.Credit)
  //      goto(ResolvingDependencies) using UnresolvedDependencies(Inputs(sender(), req))
  //  }
  //
  //  when(ResolvingDependencies, ResolveTimeout)(transform {
  //    case Event(ActorIdentity(identifier: ResolutionIdent.Value, actor @ Some(ref)),
  //      data: UnresolvedDependencies) =>
  //
  //      log.info("Resolved dependency {}, {}", identifier, ref)
  //      val newData = identifier match {
  //        case ResolutionIdent.Book   => data.copy(invClerk = actor)
  //        case ResolutionIdent.User   => data.copy(userAssociate = actor)
  //        case ResolutionIdent.Credit => data.copy(creditAssociate = actor)
  //      }
  //      stay using newData
  //  } using {
  //    case FSM.State(state, UnresolvedDependencies(inputs, Some(userAssociate),
  //      Some(invClerk), Some(creditAssociate)), _, _, _) =>
  //
  //      log.info("Resolved all dependencies, looking up entities")
  //      userAssociate ! CustomerRelationsManager.FindUserById(inputs.request.userId)
  //      val expectedBooks = inputs.request.lineItems.map(_.bookId).toSet
  //      expectedBooks.foreach(id => invClerk ! InventoryClerk.FindBook(id))
  //      goto(LookingUpEntities) using ResolvedDependencies(inputs, expectedBooks, None, Map.empty, invClerk, userAssociate, creditAssociate)
  //  })
  //
  //  when(LookingUpEntities, 10 seconds)(transform {
  //    case Event(FullResult(b: BookFO), data: ResolvedDependencies) =>
  //      log.info("Looked up book: {}", b)
  //
  //      //Make sure inventory is available
  //      val lineItemForBook = data.inputs.request.lineItems.find(_.bookId == b.id)
  //      lineItemForBook match {
  //        case None =>
  //          log.error("Got back a book for which we don't have a line item")
  //          data.originator ! unexpectedFail
  //          stop
  //
  //        case Some(item) if item.quantity > b.inventoryAmount =>
  //          log.error("Inventory not available for book with id {}", b.id)
  //          data.originator ! Failure(FailureType.Validation, InventoryNotAvailError)
  //          stop
  //
  //        case _ =>
  //          stay using data.copy(books = data.books ++ Map(b.id -> b))
  //      }
  //
  //    case Event(FullResult(u: UserFO), data: ResolvedDependencies) =>
  //      log.info("Found user: {}", u)
  //      stay using data.copy(user = Some(u))
  //
  //    case Event(EmptyResult, data: ResolvedDependencies) =>
  //      val (etype, error) =
  //        if (sender().path.name == InventoryClerk.Name) ("book", InvalidBookIdError)
  //        else ("user", InvalidUserIdError)
  //      log.info("Unexpected result type of EmptyResult received looking up a {} entity", etype)
  //      data.originator ! Failure(FailureType.Validation, error)
  //      stop
  //
  //  } using {
  //    case FSM.State(state, ResolvedDependencies(inputs, expectedBooks, Some(u),
  //      bookMap, userAssociate, invClerk, creditAssociate), _, _, _) if bookMap.keySet == expectedBooks =>
  //
  //      log.info("Successfully looked up all entities and inventory is available, charging credit card")
  //      val lineItems = inputs.request.lineItems.
  //        flatMap { item =>
  //          bookMap.
  //            get(item.bookId).
  //            map(b => SalesOrderLineItemFO(0, 0, b.id, item.quantity, item.quantity * b.cost, newDate, newDate))
  //        }
  //
  //      val total = lineItems.map(_.cost).sum
  //      creditAssociate ! CreditAssociate.ChargeCreditCard(inputs.request.cardInfo, total)
  //      goto(ChargingCard) using LookedUpData(inputs, u, lineItems, total)
  //  })
  //
  //  when(ChargingCard, 10 seconds) {
  //    case Event(FullResult(txn: CreditCardTransactionFO), data: LookedUpData) if txn.status == CreditTransactionStatus.Approved =>
  //      val order = SalesOrderFO(0, data.user.id, txn.id, SalesOrderStatus.InProgress, data.total, data.items, newDate, newDate)
  //      requestFoForSender(data.inputs.originator)
  //      persist(order, repo.persistEntity(order), id => order.copy(id = id), true)
  //
  //    case Event(FullResult(txn: CreditCardTransactionFO), data: LookedUpData) =>
  //      log.info("Credit was rejected for request: {}", data.inputs.request)
  //      data.originator ! Failure(FailureType.Validation, CreditRejectedError)
  //      stop
  //  }
  //
  //  override def postCreate(fo: SalesOrderFO) {
  //    val items = fo.lineItems.map(i => (i.bookId, i.quantity))
  //    val event = InventoryClerk.OrderCreated(fo.id, items)
  //    context.system.eventStream.publish(event)
  //  }
  //
  //  def initializedHandling: StateFunction = {
  //    case Event(UpdateOrderStatus(status), data: InitializedData[SalesOrderFO]) =>
  //      log.info("Setting status on order {} to {}", data.fo.id, status)
  //      persist(data.fo, repo.updateOrderStatus(data.fo.id, status), _ => data.fo.copy(status = status))
  //  }

}

private[order] object SalesOrderCreateValidator {

  def props = Props[SalesOrderCreateValidator]

  sealed trait State
  case object WaitingForRequest extends State
  case object ResolvingDependencies extends State
  case object LookingUpEntities extends State
  case object ChargingCard extends State

  sealed trait Data {
    def inputs: Inputs
  }
  case object NoData extends Data {
    def inputs = Inputs(ActorRef.noSender, null)
  }
  case class Inputs(originator: ActorRef, request: SalesOrder.Command.CreateOrder)
  trait InputsData extends Data {
    def inputs: Inputs
    def originator = inputs.originator
  }
  case class UnresolvedDependencies(inputs: Inputs, userAssociate: Option[ActorRef] = None,
                                    invClerk: Option[ActorRef] = None, creditAssociate: Option[ActorRef] = None) extends InputsData

  case class ResolvedDependencies(inputs: Inputs, expectedBooks: Set[String],
                                  user: Option[UserFO], books: Map[String, BookFO], userAssociate: ActorRef,
                                  invClerk: ActorRef, creditAssociate: ActorRef) extends InputsData

  case class LookedUpData(inputs: Inputs, user: UserFO,
                          items: List[SalesOrderLineItemFO], total: Double) extends InputsData

  object ResolutionIdent extends Enumeration {
    val Book, User, Credit = Value
  }

  val ResolveTimeout = 5 seconds

  val InvalidBookIdError = ErrorMessage("order.invalid.bookId", Some("You have supplied an invalid book id"))
  val InvalidUserIdError = ErrorMessage("order.invalid.userId", Some("You have supplied an invalid user id"))
  val CreditRejectedError = ErrorMessage("order.credit.rejected", Some("Your credit card has been rejected"))
  val InventoryNotAvailError = ErrorMessage("order.inventory.notavailable", Some("Inventory for an item on this order is no longer available"))
}

private[order] class SalesOrderCreateValidator extends FSM[SalesOrderCreateValidator.State, SalesOrderCreateValidator.Data] {
  import SalesOrderCreateValidator._
  import SalesOrder.Command._

  startWith(WaitingForRequest, NoData)

  when(WaitingForRequest) {
    case Event(request: CreateOrder, _) =>
      lookup(InventoryClerk.Name) ! Identify(ResolutionIdent.Book)
      lookup(CustomerRelationsManager.Name) ! Identify(ResolutionIdent.User)
      lookup(CreditAssociate.Name) ! Identify(ResolutionIdent.Credit)
      goto(ResolvingDependencies) using UnresolvedDependencies(Inputs(sender(), request))
  }

  when(ResolvingDependencies, ResolveTimeout)(transform {
    case Event(ActorIdentity(identifier: ResolutionIdent.Value, actor @ Some(ref)),
      data: UnresolvedDependencies) =>

      log.info("Resolved dependency {}, {}", identifier, ref)
      val newData = identifier match {
        case ResolutionIdent.Book   => data.copy(invClerk = actor)
        case ResolutionIdent.User   => data.copy(userAssociate = actor)
        case ResolutionIdent.Credit => data.copy(creditAssociate = actor)
      }
      stay using newData
  } using {
    case FSM.State(state, UnresolvedDependencies(inputs, Some(userAssociate),
      Some(invClerk), Some(creditAssociate)), _, _, _) =>

      log.info("Resolved all dependencies, looking up entities")
      userAssociate ! CustomerRelationsManager.FindUserByEmail(inputs.request.userEmail)
      val expectedBooks = inputs.request.lineItems.map(_.bookId).toSet
      expectedBooks.foreach(id => invClerk ! InventoryClerk.FindBook(id))
      goto(LookingUpEntities) using ResolvedDependencies(inputs, expectedBooks, None, Map.empty, invClerk, userAssociate, creditAssociate)
  })

  when(LookingUpEntities, 10 seconds)(transform {
    case Event(FullResult(b: BookFO), data: ResolvedDependencies) =>
      log.info("Looked up book: {}", b)

      //Make sure inventory is available
      val lineItemForBook = data.inputs.request.lineItems.find(_.bookId == b.id)
      lineItemForBook match {
        case None =>
          log.error("Got back a book for which we don't have a line item")
          data.originator ! unexpectedFail
          stop

        case Some(item) if item.quantity > b.inventoryAmount =>
          log.error("Inventory not available for book with id {}", b.id)
          data.originator ! Failure(FailureType.Validation, InventoryNotAvailError)
          stop

        case _ =>
          stay using data.copy(books = data.books ++ Map(b.id -> b))
      }

    case Event(FullResult(u: UserFO), data: ResolvedDependencies) =>
      log.info("Found user: {}", u)
      stay using data.copy(user = Some(u))

    case Event(EmptyResult, data: ResolvedDependencies) =>
      val (etype, error) =
        if (sender().path.name == InventoryClerk.Name) ("book", InvalidBookIdError)
        else ("user", InvalidUserIdError)
      log.info("Unexpected result type of EmptyResult received looking up a {} entity", etype)
      data.originator ! Failure(FailureType.Validation, error)
      stop

  } using {
    case FSM.State(state, ResolvedDependencies(inputs, expectedBooks, Some(u),
      bookMap, userAssociate, invClerk, creditAssociate), _, _, _) if bookMap.keySet == expectedBooks =>

      log.info("Successfully looked up all entities and inventory is available, charging credit card")
      val lineItems = inputs.request.lineItems.zipWithIndex.
        flatMap {
          case (item, idx) =>
            bookMap.
              get(item.bookId).
              map(b => SalesOrderLineItemFO(idx + 1, b.id, item.quantity, item.quantity * b.cost))
        }

      val total = lineItems.map(_.cost).sum
      creditAssociate ! CreditAssociate.ChargeCreditCard(inputs.request.cardInfo, total)
      goto(ChargingCard) using LookedUpData(inputs, u, lineItems, total)
  })

  when(ChargingCard, 10 seconds) {
    case Event(FullResult(txn: CreditCardTransactionFO), data: LookedUpData) if txn.status == CreditTransactionStatus.Approved =>
      val order = SalesOrderFO(data.inputs.request.newOrderId,
        data.user.id, txn.id, SalesOrderStatus.InProgress, data.total, data.items, newDate, newDate)
      context.parent.tell(CreateValidatedOrder(order), data.inputs.originator)
      stop

    case Event(FullResult(txn: CreditCardTransactionFO), data: LookedUpData) =>
      log.info("Credit was rejected for request: {}", data.inputs.request)
      data.originator ! Failure(FailureType.Validation, CreditRejectedError)
      stop
  }

  whenUnhandled {
    case Event(StateTimeout, data) =>
      log.error("Received state timeout in process to validate an order create request")
      data.inputs.originator ! unexpectedFail
      stop

    case Event(other, data) =>
      log.error("Received unexpected message of {} in state {}", other, stateName)
      data.inputs.originator ! unexpectedFail
      stop
  }

  def unexpectedFail = Failure(FailureType.Service, ServiceResult.UnexpectedFailure)
  def newDate = new Date
  def lookup(name: String) = context.actorSelection(s"/user/$name")

}
