package composable.errors

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CheckoutWorkflowSpec extends AnyFlatSpec with Matchers:

  trait BusinessError:
    def code: String

  final case class InvalidBasket(reason: String) extends BusinessError:
    val code = "invalid_basket"

  final case class OutOfStock(productId: String) extends BusinessError:
    val code = "out_of_stock"

  final case class PaymentDeclined(reason: String) extends BusinessError:
    val code = "payment_declined"

  final case class Basket(productId: String, paymentToken: String)
  final case class ValidBasket(productId: String, paymentToken: String)
  final case class Reservation(productId: String, paymentToken: String)
  final case class Payment(reference: String)
  final case class Order(productId: String, paymentReference: String)
  final case class HttpResponse(status: Int, body: String)

  enum CheckoutFailure:
    case InvalidBasket(reason: String)
    case Unavailable(productId: String)
    case PaymentRejected(reason: String)

  def validate(basket: Basket): Result[InvalidBasket, ValidBasket] =
    if basket.productId.trim.nonEmpty then
      Result.ok(ValidBasket(basket.productId, basket.paymentToken))
    else
      Result.fail(InvalidBasket("basket has no product"))

  def reserveStock(basket: ValidBasket): Result[OutOfStock, Reservation] =
    if basket.productId == "sold-out" then
      Result.fail(OutOfStock(basket.productId))
    else
      Result.ok(Reservation(basket.productId, basket.paymentToken))

  def takePayment(reservation: Reservation): Result[PaymentDeclined, Payment] =
    if reservation.paymentToken == "declined" then
      Result.fail(PaymentDeclined("payment provider rejected the charge"))
    else
      Result.ok(Payment(s"payment-${reservation.paymentToken}"))

  def checkout(
      basket: Basket
  ): Result[InvalidBasket | OutOfStock | PaymentDeclined, Order] =
    for
      valid       <- validate(basket)
      reservation <- reserveStock(valid)
      payment     <- takePayment(reservation)
    yield Order(reservation.productId, payment.reference)

  def toHttp(
      result: Result[InvalidBasket | OutOfStock | PaymentDeclined, Order]
  ): HttpResponse =
    result.fold(
      {
        case InvalidBasket(reason)   => HttpResponse(400, reason)
        case OutOfStock(productId)   => HttpResponse(409, s"$productId is out of stock")
        case PaymentDeclined(reason) => HttpResponse(402, reason)
      },
      order => HttpResponse(201, s"created order for ${order.productId}")
    )

  def checkoutContract(basket: Basket): Result[CheckoutFailure, Order] =
    checkout(basket).mapError {
      case InvalidBasket(reason) =>
        CheckoutFailure.InvalidBasket(reason)
      case OutOfStock(productId) =>
        CheckoutFailure.Unavailable(productId)
      case PaymentDeclined(reason) =>
        CheckoutFailure.PaymentRejected(reason)
    }

  "checkout" should "expose the exact workflow error union and create an order" in {
    val result: Result[InvalidBasket | OutOfStock | PaymentDeclined, Order] =
      checkout(Basket("keyboard", "tok-123"))

    result.toEither shouldBe Right(Order("keyboard", "payment-tok-123"))
    toHttp(result) shouldBe HttpResponse(201, "created order for keyboard")
  }

  it should "map an invalid basket at the boundary" in {
    toHttp(checkout(Basket("  ", "tok-123"))) shouldBe
      HttpResponse(400, "basket has no product")
  }

  it should "map an inventory failure at the boundary" in {
    toHttp(checkout(Basket("sold-out", "tok-123"))) shouldBe
      HttpResponse(409, "sold-out is out of stock")
  }

  it should "map a payment failure at the boundary" in {
    toHttp(checkout(Basket("keyboard", "declined"))) shouldBe
      HttpResponse(402, "payment provider rejected the charge")
  }

  it should "translate workflow errors when checkout becomes a named contract" in {
    checkoutContract(Basket("sold-out", "tok-123")).toEither shouldBe
      Left(CheckoutFailure.Unavailable("sold-out"))
  }
