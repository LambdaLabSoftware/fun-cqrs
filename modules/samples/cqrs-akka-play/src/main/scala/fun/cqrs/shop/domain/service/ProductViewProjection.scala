package fun.cqrs.shop.domain.service

import java.util.UUID

import fun.cqrs.shop.domain.model.ProductProtocol.{NameChanged, PriceChanged, ProductCreated, ProductUpdateEvent}
import fun.cqrs.shop.domain.model.{ProductId, ProductProtocol, ProductView}
import fun.cqrs.{AggregateIdentifier, HandleEvent, Projection}
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future

class ProductViewProjection(repo: ProductViewRepo) extends Projection {

  def receiveEvent: HandleEvent = {
    case e: ProductCreated => create(e)
    case e: ProductProtocol.ProductUpdateEvent => update(e)
  }

  def create(e: ProductCreated): Future[Unit] = {
    val id = ProductId.fromAggregateId(e.metadata.aggregateId)
    repo.save(ProductView(e.name, e.description, e.price, id))
  }

  def update(e: ProductUpdateEvent): Future[Unit] = {
    val id = ProductId.fromAggregateId(e.metadata.aggregateId)

    repo.updateById(id) { prod =>
      updateFunc(prod, e)
    }.map(_ => ())

  }

  private def updateFunc(view: ProductView, evt: ProductUpdateEvent): ProductView = {
    evt match {
      case e: NameChanged => view.copy(name = e.newName)
      case e: PriceChanged => view.copy(price = e.newPrice)
    }
  }
}