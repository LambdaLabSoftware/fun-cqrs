package io.funcqrs.akka.backend

import _root_.akka.actor.{ Actor, ActorRef }
import _root_.akka.util.Timeout
import _root_.akka.pattern.{ ask => akkaAsk }
import io.funcqrs.akka.AggregateManager.{ UntypedIdAndCommand, GetState, Exists }
import io.funcqrs.akka.EventsMonitorActor.Subscribe
import io.funcqrs.akka.{ EventsMonitorActor, ProjectionMonitorActor }
import io.funcqrs._
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.control.NonFatal
import scala.concurrent.duration._

case class AggregateActorRef[A <: AggregateLike](
    id: A#Id,
    aggregateManagerActor: ActorRef,
    projectionMonitor: ActorRef,
    timeoutDuration: FiniteDuration = 5.seconds
) extends AsyncAggregateRef[A]
    with AggregateAliases {

  def askTimeout = Timeout(timeoutDuration)

  def withAskTimeout(timeout: FiniteDuration): AggregateRef[A, Future] = copy(timeoutDuration = timeout)

  // need it explicitly because akka.pattern.ask conflicts with AggregatRef.ask
  private val askableActorRef = akkaAsk(aggregateManagerActor)

  def tell(cmd: Command): Unit =
    aggregateManagerActor ! UntypedIdAndCommand(id, cmd)

  def ask(cmd: Command): Future[Events] = {
    askableActorRef.ask(UntypedIdAndCommand(id, cmd))(askTimeout).mapTo[Events]
  }

  def state(): Future[A] = {

    import scala.concurrent.ExecutionContext.Implicits.global

    val eventualState = askableActorRef.ask(GetState(id))(askTimeout)

    eventualState.flatMap { res =>
      // can't use mapTo since we don't have a ClassTag for Aggregate in scope
      val tryCast = Try(res.asInstanceOf[Aggregate])
      Future.fromTry(tryCast)
    }
  }

  def isDefined(): Future[Boolean] = {
    askableActorRef.ask(Exists(id))(askTimeout).mapTo[Boolean]
  }

  def exists(predicate: A => Boolean): Future[Boolean] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    state().map(predicate)
  }

  def join(viewName: String): ViewBoundedAggregateActorRef[A] = {
    new ViewBoundedAggregateActorRef[A](this, viewName, projectionMonitor)
  }
}

class ViewBoundedAggregateActorRef[A <: AggregateLike](
    aggregateRef: AggregateActorRef[A],
    defaultView: String,
    projectionMonitorActorRef: ActorRef,
    eventsFilter: EventsFilter = All
) extends AggregateAliases {

  type Aggregate = A

  val underlyingRef = aggregateRef

  // Delegation to underlying AsyncAggregateService
  def state()(implicit timeout: Timeout, sender: ActorRef): Future[A]        = underlyingRef.state()
  def exists()(implicit timeout: Timeout, sender: ActorRef): Future[Boolean] = underlyingRef.isDefined()

  def withFilter(eventsFilter: EventsFilter): ViewBoundedAggregateActorRef[A] =
    new ViewBoundedAggregateActorRef(underlyingRef, defaultView, projectionMonitorActorRef, eventsFilter)

  def limit(count: Int): ViewBoundedAggregateActorRef[A] = withFilter(Limit(count))

  def ?(cmd: Command with CommandIdFacet)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[Events] = ask(cmd)

  def ask(cmd: Command with CommandIdFacet)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[Events] = {
    watchEvents(cmd) {
      underlyingRef ? cmd
    }
  }

  /**
    * Watch for [[DomainEvent]]s originated from the passed [[DomainCommand]] until they are applied to the ReadModel.
    *
    * @param cmd - a [[DomainCommand]] to be sent
    * @param sendCommandFunc - a function that will send the `cmd` to the Write Model.
    * @param timeout - an implicit (or explicit) [[Timeout]] after which this call will return a failed Future
    * @return - A Future with a [[Events]]. Future will complete succeffully iff the events originated from `cmd`
    *         are effectively applied on the Read Model, otherwise a [[scala.util.Failure]] holding a [[ProjectionJoinException]]
    *        is returned containing the Events and the Exception indicating the cause of the failure on the Read Model.
    *         Returns a failed Future if `Command` is not valid in which case no Events are generated.
    */
  private def watchEvents(cmd: Command with CommandIdFacet)(sendCommandFunc: => Future[Any])(implicit timeout: Timeout): Future[Events] = {

    // need it explicitly because akka.pattern.ask conflicts with AggregatRef.ask
    val askableProjectionMonitorActorRef = akkaAsk(projectionMonitorActorRef)

    import scala.concurrent.ExecutionContext.Implicits.global
    def newEventsMonitor() = {
      (askableProjectionMonitorActorRef ? ProjectionMonitorActor.EventsMonitorRequest(cmd.id, defaultView)).mapTo[ActorRef]
    }

    val resultOnWrite =
      for {
        // initialize an EventMonitor for the given command
        monitor <- newEventsMonitor()
        // send command to Write Model (AggregateManager)
        events <- sendCommandFunc.mapTo[Events]

        // need it explicitly because akka.pattern.ask conflicts with AggregatRef.ask
      } yield (akkaAsk(monitor), events)

    val resultOnRead =
      for {
        (monitor, events) <- resultOnWrite
        // apply filter and define which events we want to watch
        toWatch = eventsFilter.filter(events)
        // subscribe for events on the Read Model
        result <- (monitor ? Subscribe(toWatch)).mapTo[EventsMonitorActor.Done.type]
      } yield toWatch

    resultOnRead.recoverWith {
      // on failure, we send the events we got from the Write Model
      // together with the exception that made it fail (probably a timeout)
      case NonFatal(e) =>
        resultOnWrite.flatMap {
          case (_, evts) => Future.failed(new ProjectionJoinException(evts, e))
        }
    }
  }

  class ProjectionJoinException(val evts: Events, cause: Throwable)
      extends RuntimeException(s"Failed to join projection '$defaultView' for events $evts", cause)

}

trait EventsFilter {
  def filter[E <: DomainEvent](events: immutable.Seq[E]): immutable.Seq[E]
}

case object All extends EventsFilter {
  def filter[E <: DomainEvent](events: immutable.Seq[E]): immutable.Seq[E] = events
}

case class Limit(count: Int) extends EventsFilter {

  def filter[E <: DomainEvent](events: immutable.Seq[E]): immutable.Seq[E] = {
    events.take(count)
  }
}
