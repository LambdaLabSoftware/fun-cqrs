package io.funcqrs.test

import io.funcqrs.test.backend.InMemoryBackend
import io.funcqrs.{ AggregateLike, IdentityAggregateRef }

import scala.reflect.ClassTag

trait InMemoryTestSupport {

  private lazy val backend = {
    val backend = new InMemoryBackend
    configure(backend)
    backend
  }

  def configure(backend: InMemoryBackend): Unit

  def aggregateRef[A <: AggregateLike: ClassTag](id: A#Id): IdentityAggregateRef[A] = {
    backend.aggregateRef[A](id)
  }
}
