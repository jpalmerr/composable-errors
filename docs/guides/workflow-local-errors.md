# Workflow-local errors: when a union is the business contract

Most Scala services settle on one of two error models:

1. a broad channel such as `Throwable` or `BusinessError`, where an operation does
   not enumerate every expected failure; or
2. a named ADT such as `CheckoutFailure`, where the failures form a closed domain
   contract.

Scala 3 unions provide a useful middle position in **modelling commitment**. A
workflow can expose its exact errors structurally without first introducing a named
umbrella type. This is not a ladder of type safety: both exact unions and sealed ADTs
can be exhaustively checked.

The examples below use the real `Result` API and are compile-checked in
[`CheckoutWorkflowSpec`](../../src/test/scala/composable/errors/CheckoutWorkflowSpec.scala).

## The business scenario

A checkout application service coordinates validation, inventory, and payment. Each
operation owns its own expected business failure, but all business failures implement
an open capability used for cross-cutting metadata:

```scala
import composable.errors.Result

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
```

The individual operations stay narrow:

```scala
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

def takePayment(
    reservation: Reservation
): Result[PaymentDeclined, Payment] =
  if reservation.paymentToken == "declined" then
    Result.fail(PaymentDeclined("payment provider rejected the charge"))
  else
    Result.ok(Payment(s"payment-${reservation.paymentToken}"))
```

`checkout` composes those operations and exposes exactly the failures reachable from
that workflow:

```scala
def checkout(
    basket: Basket
): Result[InvalidBasket | OutOfStock | PaymentDeclined, Order] =
  for
    valid       <- validate(basket)
    reservation <- reserveStock(valid)
    payment     <- takePayment(reservation)
  yield Order(reservation.productId, payment.reference)
```

`Result.flatMap` explicitly returns `Result[E1 | E2, A]`. That signature preserves
the operation-specific union even though all three failures share the open
`BusinessError` parent.

## Handling the workflow at a boundary

The HTTP boundary can map every reachable business outcome without a wildcard:

```scala
final case class HttpResponse(status: Int, body: String)

def toHttp(
    result: Result[InvalidBasket | OutOfStock | PaymentDeclined, Order]
): HttpResponse =
  result.fold(
    {
      case InvalidBasket(reason) =>
        HttpResponse(400, reason)
      case OutOfStock(productId) =>
        HttpResponse(409, s"$productId is out of stock")
      case PaymentDeclined(reason) =>
        HttpResponse(402, reason)
    },
    order => HttpResponse(201, s"created order for ${order.productId}")
  )
```

On Scala 3.3.1, omitting one of those union members produces an exhaustivity warning
that identifies the missing case. This project promotes that warning to a compilation
failure with `-Xfatal-warnings`. Adding a new failure to `checkout` therefore forces
this boundary to make a decision about it.

## Why not return `BusinessError`?

This signature compiles, but communicates less:

```scala
def checkout(basket: Basket): Result[BusinessError, Order]
```

Because `BusinessError` is open, it means “some business error,” not “one of the
three checkout failures.” A caller cannot derive an exhaustive list of its concrete
implementations. It also allows checkout to return a new `BusinessError` without the
change appearing in the method's type.

A large sealed `BusinessError` hierarchy restores closed-world exhaustivity, but it
still describes the whole hierarchy rather than the subset reachable from checkout.
Consumers may be forced to handle irrelevant domain cases or discard the distinction
with a wildcard.

The exact union is useful when the public contract should remain local to this
workflow:

```scala
Result[InvalidBasket | OutOfStock | PaymentDeclined, Order]
```

## When the union should become an ADT

The decision changes when checkout failures stop being a by-product of composition
and become a first-class domain contract. Typical signals are:

- other modules should depend on checkout failures without depending on validation,
  inventory, or payment implementation types;
- the failures need a stable JSON codec, OpenAPI schema, event schema, or persisted
  representation;
- multiple workflows intentionally share the complete taxonomy;
- the domain needs to control which checkout failures can exist; or
- internal error changes should not alter checkout's public API.

At that point, the translation cost creates useful abstraction:

```scala
enum CheckoutFailure:
  case InvalidBasket(reason: String)
  case Unavailable(productId: String)
  case PaymentRejected(reason: String)

def checkoutContract(basket: Basket): Result[CheckoutFailure, Order] =
  checkout(basket).mapError {
    case InvalidBasket(reason) =>
      CheckoutFailure.InvalidBasket(reason)
    case OutOfStock(productId) =>
      CheckoutFailure.Unavailable(productId)
    case PaymentDeclined(reason) =>
      CheckoutFailure.PaymentRejected(reason)
  }
```

The ADT is not preferable because it is more exhaustive: the exact union was already
exhaustive. It is preferable because it gives checkout a nominally owned, stable
contract that is decoupled from its collaborators.

## The decision rule

Use a union when the error set belongs to one composition and should expose its exact
reachable failures. Introduce an ADT when that error set becomes a named domain
concept with its own ownership, invariants, or external contract.

| Model | Best fit |
|---|---|
| Broad error channel | Callers deliberately treat expected failures uniformly |
| Exact union | A workflow exposes its precise, local set of reachable failures |
| Named ADT | The error family is itself a stable domain or integration contract |

## Boundaries of the approach

The union approach is narrower than it first appears:

- Standard Scala 3 `Either` already infers unions for errors with no useful common
  supertype. `Result` differs when ordinary inference widens related errors to their
  common parent.
- The precision belongs to signatures that explicitly produce `E1 | E2`, not to the
  `Result` type universally. `sequence` and `traverse` can infer a common supertype
  from a soft union unless the input is explicitly typed.
- Scala 3 cannot subtract a handled member from a union. Complete boundary handling
  is natural; progressively narrowing the remaining error channel is not.
- Generic alternatives with the same erased JVM type, such as `ApiError[String]` and
  `ApiError[Int]`, cannot be distinguished by their type arguments at runtime.
- `Result` is fail-fast. Use an accumulating abstraction when the business operation
  must report multiple validation failures together.

The compiler probes behind these conclusions are recorded in
[`type-inference-probes.md`](../research/type-inference-probes.md).
