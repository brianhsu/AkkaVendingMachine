import akka.actor._
import akka.pattern.ask
import akka.routing._
import akka.util._
import java.util.concurrent.TimeUnit
import scala.concurrent._

sealed trait MapReduceMessage
case class MapData(dataList: List[String]) extends MapReduceMessage
case class ReducedData(reduceDataMap: Map[String, Int]) extends MapReduceMessage
case object Result extends MapReduceMessage

class MapActor extends Actor {

  val randomNumber = scala.util.Random.nextInt(1000)

  val STOP_WORDS_LIST = List(
    "a", "am", "an", "and", "are", "as", "at", "be","do", "go", "if", "in", "is", "it", "of", "on", "the", "to"
  )

  def receive = {
    case msg: String => 
      println(s"Processed $msg in ${System.identityHashCode(this)}, random number is $randomNumber")
      sender ! evaluateExpression(msg)
  }

  def evaluateExpression(line: String): MapData = {
    val nonStopWords = line.split("\\s+").filterNot(STOP_WORDS_LIST contains _).toList
    MapData(nonStopWords)
  }
}

class ReduceActor extends Actor {
  def receive = {
    case MapData(dataList) => sender ! reduce(dataList)
  }

  def reduce(words: List[String]): ReducedData = {
    var wordCountMap: Map[String, Int] = words.groupBy(x => x).mapValues(_.size)
    ReducedData(wordCountMap)
  }
}

class AggregateActor extends Actor {
  var finalReducedMap: Map[String, Int] = Map.empty
  def receive = {
    case ReducedData(reduceDataMap) => 
      aggregateInMemoryReduce(reduceDataMap)

    case Result =>  
      sender ! finalReducedMap
  }

  def aggregateInMemoryReduce(reduceList: Map[String, Int]) = {
    reduceList.foreach { case(word, count) =>
      val newCount = finalReducedMap.get(word).getOrElse(0) + count
      finalReducedMap = finalReducedMap.updated(word, newCount)
    }

  }
}


class MasterActor extends Actor {
  val mapActor = context.actorOf(Props[MapActor], name = "map")
  val reducedActor = context.actorOf(RoundRobinPool(5).props(Props[ReduceActor]), name = "reduced")
  val aggregateActor = context.actorOf(Props[AggregateActor], name = "aggregateActor")


  def receive = {
    case line: String => mapActor ! line
    case mapData: MapData => reducedActor ! mapData
    case reducedData: ReducedData => aggregateActor ! reducedData
    case Result => aggregateActor forward Result

  }
}

object Main {
  def main(args: Array[String]) {
    val system = ActorSystem("MapReduceApp")
    val master = system.actorOf(Props[MasterActor], name ="master")
    implicit val timeout = Timeout(5, TimeUnit.SECONDS)

    master ! "The quick brown fox tried to jump over the lazy dog and fell on the dog"
    master ! "Dog is man's best friend"
    master ! "Dog and Fox belong to the same family"

    Thread.sleep(500)

    val t = (master ? Result).mapTo[Map[String, Int]]
    val result = Await.result(t, timeout.duration)

    val answer = Map("lazy" -> 1, "jump" -> 1, "best" -> 1, "fell" -> 1, "friend" -> 1, "Fox" -> 1, "dog" -> 2, "belong" -> 1, "man's" -> 1, "The" -> 1, "over" -> 1, "same" -> 1, "Dog" -> 2, "brown" -> 1, "tried" -> 1, "quick" -> 1, "family" -> 1, "fox" -> 1)

    println(result == answer)

    system.terminate()


  }
}

import akka.actor.{ ActorRef, FSM }
import scala.concurrent.duration._

final case class SetTarget(ref: ActorRef)
final case class Queue(obj: Any)
case object Flush
final case class Batch(obj: Seq[Any])

sealed trait State
case object Idle extends State
case object Active extends State

sealed trait Data
case object Uninitialized extends Data
case class Todo(target: ActorRef, queue: Seq[Any]) extends Data

class TestActor extends Actor {
  def receive: Receive = {
    case e => println(s"I got message: $e")
  }
}

class Buncher extends FSM[State, Data] {

  startWith(Idle, Uninitialized)

  when(Idle) {
    case Event(SetTarget(ref), Uninitialized) => 
      stay using Todo(ref, Vector.empty)
  }

  when(Active, stateTimeout = 1.second) {
    case Event(Flush, t: Todo) => goto(Idle) using t.copy(queue = Vector.empty)
    case Event(StateTimeout, t: Todo) => println("In StateTimeout"); goto(Idle) using t.copy(queue = Vector.empty)

  }

  whenUnhandled {
    case Event(Queue(obj), t @ Todo(_, v)) => 
      goto(Active) using t.copy(queue = v :+ obj)
    case Event(e, s) =>
      println("received unhandled request {} in state {}/{}", e, stateName, s)
      stay
  }

  onTransition {
    case Active -> Idle => 
      println("I swtiched to idle...")
      stateData match {
        case Todo(ref, queue) => ref ! Batch(queue)
        case _ => println("Nothing to do")
      }
  }

  onTransition {
    case Active -> Idle => 
      println("I swtiched to idle 2...")
  }


  initialize()

}

object FSMTest {
  val system = ActorSystem("MapReduceApp")
  val testActor = system.actorOf(Props[TestActor])
  val buncher = system.actorOf(Props[Buncher])
  def main(args: Array[String]) {
    testActor ! "AAAAA"
    buncher ! "S1"
    buncher ! SetTarget(testActor)
    buncher ! "S2"
    buncher ! Queue(42)
    Thread.sleep(5000)
    buncher ! "S3"
    buncher ! Queue(43)
    buncher ! "S4"
    buncher ! Queue(44)
    buncher ! "S5"
    buncher ! Queue(45)
    buncher ! "S6"
    buncher ! Flush
    buncher ! "S7"



    Thread.sleep(1000)
    system.terminate()

  }
}

