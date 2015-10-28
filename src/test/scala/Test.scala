import akka.actor._
import akka.testkit._
import org.scalatest._
import scala.concurrent.duration._

class VendingMachineSpec(system: ActorSystem) extends TestKit(system) 
                                              with ImplicitSender with FunSpecLike 
                                              with Matchers with BeforeAndAfterAll {

  import VendingMachine._

  describe("一台自動販賣機") {


    it ("要能接受硬幣而且回傳正確的可選商品列表") {
      val vendingMachine = system.actorOf(Props[VendingMachine])
      vendingMachine ! InsertCoins(10)
      expectMsg(SelectableProducts(10, Map("生活紅茶"-> Product(10,200))))
      vendingMachine ! InsertCoins(15)
      expectMsg(SelectableProducts(25, Map("生活紅茶" -> Product(10,200), "可口可樂" -> Product(25,100), "無糖綠茶" -> Product(20,200), "雪碧" -> Product(25,100))))
    }

    it ("要可以在使用者按下退幣鈕後退正確的金額給使用者") {
      val vendingMachine = system.actorOf(Props[VendingMachine])

      vendingMachine ! InsertCoins(10)
      vendingMachine ! InsertCoins(15)
      vendingMachine ! Refund

      expectMsgType[SelectableProducts]
      expectMsgType[SelectableProducts]
      expectMsg(Change(25))

      vendingMachine ! InsertCoins(100)
      vendingMachine ! Refund
      expectMsgType[SelectableProducts]
      expectMsg(Change(100))
    }


    it ("要可以出貨使用者選的商品並找零") {
      val vendingMachine = system.actorOf(Props[VendingMachine])

      vendingMachine ! InsertCoins(50)
      vendingMachine ! InsertCoins(10)
      vendingMachine ! InsertCoins(5)

      expectMsgType[SelectableProducts]
      expectMsgType[SelectableProducts]
      expectMsgType[SelectableProducts]

      vendingMachine ! Selection("可口可樂")

      expectMsgAllOf(10.second, SendProduct("可口可樂"), Change(40))
    }

    it ("出貨和找零後，販賣機要回到 Idle 狀態，而且投幣金額要為 0 且商品數量被更新") {
      import akka.testkit.TestFSMRef
      implicit val actorSystem = system
      val vendingMachine = TestFSMRef(new VendingMachine)

      vendingMachine ! InsertCoins(65)
      vendingMachine ! Selection("可口可樂")
      vendingMachine.stateName shouldBe Idle
      vendingMachine.stateData.currentMoneyInside shouldBe 0
      vendingMachine.stateData.products.get("可口可樂").map(_.stock) shouldBe Some(99)
    }
  }

  def this() = this(ActorSystem("MySpec"))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
}
