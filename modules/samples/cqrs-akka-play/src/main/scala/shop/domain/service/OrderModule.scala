package shop.domain.service

import akka.actor.{ActorRef, Props}
import com.softwaremill.macwire._
import io.strongtyped.funcqrs.akka.{AssignedAggregateId, AggregateManager, ProjectionActor}
import io.strongtyped.funcqrs.{Behavior, Tag}
import shop.api.AkkaModule
import shop.app.LevelDbProjectionSource
import shop.domain.model.{Order, OrderNumber, OrderView}

trait OrderModule extends AkkaModule {

  val orderAggregateManager: ActorRef @@ Order.type =
    actorSystem
      .actorOf(Props[OrderAggregateManager], "orderAggregateManager")
      .taggedWith[Order.type]


  //----------------------------------------------------------------------
  // READ side wiring
  val orderViewRepo = wire[OrderViewRepo]
  val productViewRepoForOrder = wire[ProductViewRepo].taggedWith[OrderView.type]
  val customerViewRepoForOrder = wire[CustomerViewRepo].taggedWith[OrderView.type]

  funCQRS.projection(Props(classOf[OrderViewProjectionActor], wire[OrderViewProjection]), "OrderViewProjectionActor")

}

class OrderAggregateManager extends AggregateManager with AssignedAggregateId {
  type AggregateType = Order
  def behavior(id: OrderNumber): Behavior[Order] = Order.behavior(id)
}

class OrderViewProjectionActor(val projection: OrderViewProjection) extends ProjectionActor with LevelDbProjectionSource {
  val tag: Tag = Order.dependentView
}