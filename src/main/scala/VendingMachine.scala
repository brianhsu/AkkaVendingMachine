import akka.actor._
import scala.concurrent.duration._

object VendingMachine {

  // 自動販賣機會用到的資料：目前投了多少錢，有什麼產品，使用者選了什麼產品
  case class Product(price: Int, stock: Int)
  case class MoneyAndProducts(currentMoneyInside: Int, products: Map[String, Product], selectedItem: Option[String] = None)

  // 自動販賣機可能的狀態
  sealed trait State
  case object Idle extends State
  case object Selection extends State
  case object Dispending extends State

  // 自動賦販賣機可以接受的事件
  case class InsertCoins(coins: Int)
  case class Selection(productName: String)
  case object Refund
  case object Done
  case object AskAvaliableProducts

  // 送回給 UI 的資料
  case class SendProduct(item: String)
  case class Change(coins: Int)
  case class SelectableProducts(currentMony: Int, items: Map[String, Product])

  // 一開始的產品價格和資料
  val defaultStocks = Map(
    "可口可樂" -> Product(25, 100),
    "雪碧"     -> Product(25, 100),
    "生活紅茶" -> Product(10, 200),
    "無糖綠茶" -> Product(20, 200),
    "伯朗咖啡" -> Product(30, 50)
  )


}

class VendingMachine extends FSM[VendingMachine.State, VendingMachine.MoneyAndProducts] {

  import VendingMachine._

  // 一開始自動販賣機在 Idle 的狀態，沒有人投錢，商品是滿的
  startWith(Idle, MoneyAndProducts(0, defaultStocks))

  /**
   *  檢查使用者投的錢是否夠買某個商品和該商品庫存是否足夠
   *
   *  @param  item          使用者想買的商品
   *  @param  currentData   目前自動販賣機裡投了多少錢，有什麼商品
   *  @return               若商品有庫存，而且機器裡的錢也夠則為 true
   */
  def productIsAavaliable(item: String, currentData: MoneyAndProducts): Boolean = {
    currentData.products.get(item) match {
      case Some(Product(price, stock)) if (stock > 0 && currentData.currentMoneyInside >= price) => true
      case _ => false
    }
  }

  /**
   *  買了商品之後的新狀態：
   *
   *    - 商品庫存數量減一
   *    - 自動販賣機裡的錢歸零
   *
   *  @param  currentData   原本的自動販賣機資料
   *  @param  item          要買的商品
   *  @return               新的自動販賣機資料
   */
  def newData(currentData: MoneyAndProducts, itemName: String) = {
    val newProduct = currentData.products.get(itemName).map(x => x.copy(stock = x.stock -1)).get
    val newProductList = currentData.products.updated(itemName, newProduct)
    currentData.copy(
      currentMoneyInside = 0,
      products = newProductList,
      selectedItem = Some(itemName)
    )
  }

  // 當自動販賣機在 Idle 狀態
  when(Idle) {
    // 接收到投幣事件時，轉到 Selection 狀態，並且把使用者投的錢加上去
    case Event(InsertCoins(inserted), currentData) => 
      val totalMoney = currentData.currentMoneyInside + inserted
      goto(Selection) using currentData.copy(currentMoneyInside = totalMoney)
  }

  // 當在 Selection 狀態
  when(Selection) {
    // 繼續投幣的話就繼續停在 Selection 狀態
    case Event(InsertCoins(inserted), currentData) => 
      val totalMoney = currentData.currentMoneyInside + inserted
      goto(Selection) using currentData.copy(currentMoneyInside = totalMoney)

    // 按下商品按鈕，而且可以買的話，就轉到出貨狀態
    case Event(Selection(item), currentData) if productIsAavaliable(item, currentData) =>
      goto(Dispending) using newData(currentData, item)

    // 如果按下退幣鈕，那就回到 Idle 的狀態，販賣機的投幣金額應該也要歸零。
    case Event(Refund, currentData) =>
      goto(Idle) using currentData.copy(currentMoneyInside = 0)
  }

  // 如果是在出貨狀態
  when(Dispending) {
    // 收到出完貨的訊息，那就回到 Idle 等下一次的投幣，已投金額也歸零
    case Event(Done, currentData) => 
      goto(Idle) using currentData.copy(currentMoneyInside = 0)
  }

  // 從某個狀態轉換到另一個狀態，應該告知 UI 做一些事
  onTransition {

    // 如果是從 Idle 轉到投幣，或使用者持續投幣，
    // 那要告知使用者現在投了多少錢，可以選什麼商品（錢夠而且有庫存）。
    case Idle -> Selection | Selection -> Selection => 
      val MoneyAndProducts(currentMoneyInside, products, _) = nextStateData
      val avaliableItems = products.filter{case (name, product) => product.price <= currentMoneyInside}
      sender ! SelectableProducts(currentMoneyInside, avaliableItems)

    // 如果從 Selection 轉到 Idle（按下退幣）
    // 那就把原來投的零錢還給使用者
    case Selection -> Idle =>
      val MoneyAndProducts(currentMoneyInside, products, _) = stateData
      sender ! Change(currentMoneyInside)

    // 如果從 Selection 轉到 Dispending，那就是出貨，
    // 先把商品送出，然後找零，最後再發出出貨完成的事件給自動販賣機自己。
    case Selection -> Dispending =>
      val MoneyAndProducts(oldMoneyInside, _, _) = stateData
      val MoneyAndProducts(_, products, Some(selectedItem)) = nextStateData
      val change = products.get(selectedItem).map(p => oldMoneyInside - p.price).getOrElse(0)
      sender ! SendProduct(selectedItem)
      sender ! Change(change)
      self ! Done
  }

  whenUnhandled {
    case Event(AskAvaliableProducts, data) => 
      val avaliableItems = data.products.filter{case (name, product) => product.price <= data.currentMoneyInside}
      sender ! SelectableProducts(data.currentMoneyInside, avaliableItems)
      stay()
  }
}

class VendingMachineUI extends Actor {

  import VendingMachine._

  val vendingMachine = context.actorOf(Props[VendingMachine], name = "StateMachine")

  override def preStart() {
    vendingMachine ! AskAvaliableProducts
  }
  
  def printMenuAndWait(currentMoneyInside: Int, products: Map[String, Product]) {

    val sortedItems = products.toList.sortWith(_._1 <= _._1)
    var selectionToItem: Map[String, String] = Map.empty
    val sortedMenus = sortedItems.zipWithIndex.map { case ((name, productInfo), index) =>
      selectionToItem += (index.toString -> name)
      s"$index. $name / 價格：${productInfo.price} / 庫存：${productInfo.stock} "
    }

    println(s"========== NT: $currentMoneyInside ===============")
    println("A. 投五元")
    println("B. 投十元")
    println("C. 投五十元")
    println("D. 退幣")
    sortedMenus.foreach(println)
    println("Q. 結束")
    println(s"=========================")
    print("請選擇：")

    val choice = scala.io.StdIn.readLine()

    choice match {
      case "A" => vendingMachine ! InsertCoins(5)
      case "B" => vendingMachine ! InsertCoins(10)
      case "C" => vendingMachine ! InsertCoins(50)
      case "D" => 
        vendingMachine ! Refund
        vendingMachine ! AskAvaliableProducts
        
      case "Q" => context.system.terminate()
      case selection =>

        selectionToItem.get(selection) match {
          case None => vendingMachine ! AskAvaliableProducts
          case Some(itemName) =>
            vendingMachine ! Selection(itemName)
            vendingMachine ! AskAvaliableProducts
        }

    }
  }


  def receive = {
    case SelectableProducts(currentMoneyInside, products) => 
      printMenuAndWait(currentMoneyInside, products)
    case SendProduct(item) =>
      println(s"拿到商品 $item")
    case Change(coins) => 
      println(s"拿到找零：$coins")
  }

}

object Main {

  def main(args: Array[String]) {
    val actorSystem = ActorSystem("VendingMachineApplication")
    val vendingMachineUI = actorSystem.actorOf(Props[VendingMachineUI], name ="ui")
  }
}
