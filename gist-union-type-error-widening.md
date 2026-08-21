# What I Learned Building Error Handling with Scala 3 Union Types

---

## The Problem

Every Scala service I have worked on that chooses to model errors eventually writes
some version of this boilerplate:

```scala
// Create a shared application error hierarchy
sealed trait AppError
case class ValidationFailed(error: ValidationError) extends AppError
case class DatabaseFailed(error: DatabaseError) extends AppError
case class NetworkFailed(error: NetworkError) extends AppError

// Map each operation into it at composition boundaries
for
  input <- validate(raw).left.map(ValidationFailed.apply)
  user  <- findUser(input.id).left.map(DatabaseFailed.apply)
  data  <- fetchRemote(user).left.map(NetworkFailed.apply)
yield data
```

This can also appear with ZIO or Cats Effect when a codebase chooses a shared
application error ADT, although neither library requires that design.

The costs are familiar:

- every new error requires a wrapper case;
- every composition boundary needs an explicit error mapping;
- the umbrella hierarchy grows with the application; and
- refactoring the hierarchy touches code unrelated to the business operation.

My initial claim was that Scala 3 needed a new `Result` type to avoid this. That was
too broad. Standard Scala 3 `Either` already infers a union when the errors have no
useful common supertype.

The interesting difference appears when the errors *do* share a common parent:

| Error types | Standard `Either` infers | `Result` infers |
|---|---|---|
| Unrelated case classes | `Either[ErrA \| ErrB, A]` | `Result[ErrA \| ErrB, A]` |
| `Missing` and `Invalid` extending `DomainError` | `Either[DomainError, A]` | `Result[Missing \| Invalid, A]` |
| `ExcA` and `ExcB` extending `Exception` | `Either[Exception, A]` | `Result[ExcA \| ExcB, A]` |

That narrower result is the actual subject of this project: preserving the exact
failures reachable from an operation when ordinary inference would widen them to a
common supertype.

---

## The Inspiration

Unison's direct style and composable abilities were the inspiration. I wanted to see
how far Scala 3 union types could take the same underlying idea without adopting a
new effect system.

The core is an opaque alias over `Either` whose `flatMap` explicitly returns the
union of the existing and next error types:

```scala
opaque type Result[+E, +A] = Either[E, A]

extension [E, A](self: Result[E, A])
  def flatMap[E2, B](f: A => Result[E2, B]): Result[E | E2, B] =
    self match
      case Left(error)  => Left(error)
      case Right(value) => f(value)
```

Scala 3 distinguishes between *soft* unions synthesised by inference and *hard*
unions written explicitly in a signature. When a bare type parameter is inferred,
the compiler can widen a soft union to its common parent. Writing `E | E2` in the
return type makes the union hard and preserves its members.

The useful property therefore belongs to the signature shape, not to `Result` as a
type.

---

## The Business-Logic Use Case

The strongest use case is application-service orchestration. One workflow composes
several domain operations, and its public error type should expose only the failures
that workflow can actually produce.

Consider checkout. Validation, inventory, and payment errors share an open
`BusinessError` capability used for cross-cutting metadata:

```scala
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

Each operation owns one expected failure:

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

The workflow composes without wrapper cases or `leftMap` calls:

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

The exact union is valuable at the HTTP boundary:

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

With `-Xfatal-warnings`, adding another error to `checkout` forces this handler to
decide how that new business outcome maps to the protocol.

### Why Not Return `BusinessError`?

This signature is broader:

```scala
def checkout(basket: Basket): Result[BusinessError, Order]
```

Because `BusinessError` is open, it means “some business error,” not “one of the
three checkout failures.” The compiler cannot enumerate all possible implementations,
and a new error can enter the workflow without appearing in its return type.

A large sealed `BusinessError` hierarchy can still provide exhaustiveness, but it
describes the whole hierarchy rather than the subset reachable from checkout. A
consumer may have to handle irrelevant domain errors or use a wildcard.

The union preserves an operation-specific contract without introducing a new named
hierarchy solely to make the operations compose.

### When to Introduce an ADT

The balance changes when checkout failures stop being an incidental result of
composition and become a first-class domain contract. A named `CheckoutFailure` ADT
is justified when:

- other modules should depend on checkout without depending on its collaborators'
  error types;
- the failures need a stable JSON, OpenAPI, event, or persistence schema;
- several workflows intentionally share the complete taxonomy;
- the domain must control which checkout failures can exist; or
- internal validation, inventory, or payment changes should not alter the public API.

At that point, translating into an ADT buys real decoupling. The ADT is not preferable
because it is more exhaustive: the exact union was already exhaustive. It is
preferable because the error family has become a nominally owned, stable contract.

The decision rule is:

> Use a union when the error set belongs to one composition and should expose its
> exact reachable failures. Introduce an ADT when that error set becomes a named
> domain concept with its own ownership, invariants, or external contract.

---

## What Worked

### Composition Preserves Exact Members

For `flatMap` chains, callers do not construct unions manually. Each `E | E2` return
type preserves the members accumulated so far.

A comprehension containing more than ten error types compiled without inference
timeouts in this experiment. That is evidence for this use case, not a general
compiler-performance guarantee.

### Exact Unions Are Exhaustively Checked

My original gist got this wrong. Given `ExcA | ExcB`, a match containing only `ExcA`
produces `E029` and names `ExcB` as the missing case. Under this project's compiler
flags, that is a compilation failure.

Adding a member to an exact union also invalidates existing exhaustive consumers. A
change from `A | B` to `A | B | C` forces a boundary handler to account for `C`.

### There Is No Wrapper Allocation

`Result` is an opaque type over `Either`, so it does not allocate another runtime
wrapper. The union is compile-time type information; concrete, reifiable error
classes are what the JVM distinguishes during pattern matching.

---

## Where It Breaks Down

### Precision Depends on Every Signature

`Result` does not preserve exact unions universally. If `ExcA` and `ExcB` extend
`Exception`, this expression infers `Result[Exception, List[Int]]`:

```scala
Result.sequence(List(resultA, resultB))
```

`traverse` has the same limitation. Their signatures infer a bare `E` from a soft
union, allowing the compiler to widen it before the method body can do anything.
Supplying an explicit union type argument or an input already typed with a hard union
preserves the members.

This is a limitation to document, not a traversal bug that can be fixed internally.

### Exhaustiveness Is Lost When Open-Parent Widening Occurs

Exact unions are exhaustive, but a value widened to an open parent such as
`Exception` no longer carries a closed set of alternatives. Matching only `ExcA`
against `Exception` compiles because the compiler cannot enumerate every possible
subtype.

A sealed common parent behaves differently. Widening `Missing | Invalid` to a sealed
`DomainError` can retain hierarchy-wide exhaustiveness, but loses the narrower fact
that one particular operation produces only `Missing` and `Invalid`.

The real guarantee is therefore conditional: exact unions provide operation-specific
exhaustiveness for as long as their precision survives.

### There Is No Type Subtraction

Scala 3 has no type operator for `(A | B | C) - A = B | C`. You can handle one
member at runtime, but cannot express that it has been removed from the remaining
error channel:

```scala
// Scala cannot compute the returned E by subtracting ValidationError.
def handleValidation[E, A](
    result: Result[E | ValidationError, A]
): Result[E, A] = ???
```

Complete boundary handling is natural. Composable handlers that progressively narrow
the error channel are not.

### Generic Type Arguments Are Erased

`ApiError[String]` and `ApiError[Int]` erase to the same JVM class and cannot be
distinguished by tests on their type arguments. Matching on reifiable payload values
can work, but the generic arguments themselves are unavailable at runtime.

---

## Conclusion

There are three useful ways to expose expected errors:

1. **A broad, non-enumerated channel** such as `Throwable` or an open error parent.
   Operations do not publish each concrete alternative.
2. **A structural union.** A workflow publishes its exact, local alternatives without
   creating a named umbrella type.
3. **A named ADT.** The error family is a closed domain concept with explicit ownership
   and a stable nominal contract.

The union sits between the other two in modelling commitment, not in type safety.
Exact unions and sealed ADTs can both provide exhaustive handling.

In codebases I have worked on, teams usually standardise on the first or third model.
This investigation showed that the second model has a concrete business use case:
workflow-local error contracts that are more precise than a broad parent but do not
yet justify a named ADT.

The use case is narrower than I first expected. Standard `Either` already handles
unrelated errors, precision depends on union-producing signatures, collection
operations can widen it away, and Scala cannot subtract handled members.

`Result` therefore does not invent union-typed error composition. It demonstrates
where explicitly union-producing APIs preserve more operation-level information than
ordinary generic inference. That is a valid position between a broad error channel
and a named domain ADT, but not a universal replacement for either.

---

The compiler behaviour described here was verified against Scala 3.3.1 with
`-Xfatal-warnings`. The probes and counterexamples are recorded in
[the repository](https://github.com/jpalmerr/composable-errors/blob/main/docs/research/type-inference-probes.md).

**Repo**: [composable-errors on GitHub](https://github.com/jpalmerr/composable-errors)
