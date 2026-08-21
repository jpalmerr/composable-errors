# Compiler probe results: union widening in `Either` vs `Result`

Every table below is the observed output of the Scala 3 compiler, not a prediction.

**Environment**: Scala 3.3.1, sbt 1.10.11, JDK 25.0.3, `scalacOptions = -deprecation -feature -unchecked -Xfatal-warnings`.

**Method**: annotate an expression with a deliberately wrong type (`: String`) and read the inferred
type out of the `E007` mismatch message. Annotating with `: Unit` does **not** work — value discarding
silently accepts any expression, which is why an earlier round of probes compiled clean and proved nothing.

```scala
val probe: String =           // error: Found: Either[ExcA | ExcB, Int]  Required: String
  for
    a <- eExcA
    b <- eExcB
  yield a + b
```

---

## 1. What the error channel infers, with no type annotation

Two operations, each failing with its own error type, composed in a for-comprehension.

| Error types | standard `Either` | `Result` |
|---|---|---|
| Two unrelated case classes | `Either[ErrA \| ErrB, Int]` | `Result[ErrA \| ErrB, Int]` |
| Two unrelated plain `final class`es | `Either[PlainA \| PlainB, Int]` | (not probed — same shape) |
| Two case classes extending `Exception` | **`Either[Exception, Int]`** | `Result[ExcA \| ExcB, Int]` |
| Two case classes of a `sealed trait DomainError` | **`Either[DomainError, Int]`** | `Result[Missing \| Invalid, Int]` |

`Either` preserves the union when the members have no useful common supertype, and collapses to the
common supertype when they do. `Result` preserves the exact union in all four cases.

A three-step `Result` chain infers `Result[ExcA | (ExcB | ExcC), Int]` — nested in the *display*, flat
in semantics.

## 2. Why

Scala 3 distinguishes **hard** unions (written explicitly with `|` in a signature) from **soft** unions
(synthesised by inference as the lub of two types). When the compiler instantiates a type variable it
applies `widenUnion`, which replaces a *soft* top-level union with its join. Hard unions are left alone.

```scala
// Either: A1 is a bare type variable. Inference produces the SOFT union ExcA | ExcB,
// then widens it to the join, Exception.
def flatMap[A1 >: A, B1](f: B => Either[A1, B1]): Either[A1, B1]

// Result: the union is WRITTEN in the return type, so it is hard. Nothing is inferred, nothing widens.
def flatMap[E2, B](f: A => Result[E2, B]): Result[E | E2, B]
```

Two consequences that follow from this, and were confirmed:

- Widening applies at the **top level only**. `List(rExcA, rExcB)` infers
  `List[Result[ExcA | ExcB, Int]]` — the soft union sits under a type constructor and survives.
- The property belongs to the **signature shape**, not to the `Result` type. Any signature taking a
  bare error type variable re-introduces the widening — including `Result`'s own (see §3).

## 3. Where `Result` leaks the same precision

| Expression | Inferred |
|---|---|
| `Result.sequence(List(rExcA, rExcB))` | **`Result[Exception, List[Int]]`** |
| `Result.traverse(List(1, 2))(i => if i > 1 then rExcA else rExcB)` | **`Result[Exception, List[Int]]`** |
| `Result.fail(if c then ExcA("a") else ExcB("b"))` | **`Result[Exception, Nothing]`** |
| `Result.sequence[ExcA \| ExcB, Int](List(rExcA, rExcB))` | `Result[ExcA \| ExcB, List[Int]]` |
| `Result.sequence(List(f0))` where `f0: Result[ExcA \| ExcB, Int]` | `Result[ExcA \| ExcB, List[Int]]` |

The last two rows locate the cause. The leak is **not** inside `sequence`. `List(rExcA, rExcB)` has a
*soft* union element type; `sequence` merely instantiates its bare `E` from it, at which point the
widening fires. Feed it a value whose union is already hard (`f0`) and nothing is lost. So this is not
fixable by rewriting `sequence` — the softness is present in the argument before `sequence` is applied.
The workarounds are an explicit type argument, or binding the precise value to a `val` first.

## 4. Where `Result` keeps precision

| Expression | Inferred |
|---|---|
| `Result.zip(rExcA, rExcB)` | `Result[ExcA \| ExcB, (Int, Int)]` |
| `Result.map2(rExcA, rExcB)(_ + _)` | `Result[ExcA \| ExcB, Int]` |
| `rExcA.recoverWith { case _: ExcA => rExcB }` | `Result[ExcA \| ExcB, Int]` |
| `rExcA.recover { case _: ExcA => 0 }` | `Result[ExcA, Int]` |
| `rExcA.mapError(e => ExcB(e.msg))` | `Result[ExcB, Int]` |
| `rExcA.orElse(rExcB)` | `Result[ExcB, Int]` — `E` dropped by design |

These all declare their unions (`E1 | E2`, `E | E2`) rather than inferring a bare variable.

## 5. Exhaustivity over unions

| Selector | Branches | Result |
|---|---|---|
| `ExcA \| ExcB` | `ExcA` only | **`E029`: "match may not be exhaustive. It would fail on pattern case: `ExcB(_)`"** |
| `ExcA \| ExcB` | `ExcA`, `ExcB` | compiles clean |
| `ExcA \| ExcB` | `ExcA`, `case _` | compiles clean — precision discarded silently |
| `Exception` | `ExcA` only | **compiles clean — no exhaustivity check at all** |

Exhaustivity checking over an exact union is real, and the diagnostic names the missing member. Under
`-Xfatal-warnings` it is a hard error. Widening the selector to a common supertype is what destroys
the check — which is the same widening `Either` performs automatically in §1.

### Library evolution

Adding a member to a union **does** break existing consumers, in both handler styles:

```scala
def widened: Result[ExcA | ExcB | ExcC, Int] = ...   // ExcC newly added

widened.fold({ case _: ExcA => "a"; case _: ExcB => "b" }, _.toString)
// E029: It would fail on pattern case: ExcC(_)

widened.toEither match
  case Left(_: ExcA) => "a"
  case Left(_: ExcB) => "b"
  case Right(n)      => n.toString
// E029: It would fail on pattern case: Left(ExcC(_))
```

A case-block literal passed as `fold`'s `E => B` argument is exhaustivity-checked, same as an explicit
`match`. This refutes the claim that union types cannot enforce completeness the way sealed traits do.

## 6. Generic error types are not distinguishable

```scala
case class ApiError[T](payload: T)

def d(e: ApiError[String] | ApiError[Int]): String = e match
  case _: ApiError[String] => "string"
  case _: ApiError[Int]    => "int"
```

Both branches are a **hard compile error**, not an unchecked warning:

> the type test for `ApiError[String]` cannot be checked at runtime because its type arguments can't be
> determined from `ApiError[Int]`

Discriminating on the payload instead compiles clean, because that test is reifiable:

```scala
case ApiError(s: String) => "string"
case ApiError(i: Int)    => "int"
```

## 7. Summary

Verified:

- `Result` preserves an exact error union where `Either` collapses it to a common supertype. The
  practically important case is two members of a `sealed trait` — the very pattern the union approach
  claims to replace.
- Exhaustivity checking over exact unions works, names missing members, and breaks consumers on union
  growth.

Verified *against* the project's earlier claims:

- Generic error types (`ApiError[String]` vs `ApiError[Int]`) are **not** distinguishable.
- `Result`'s own `sequence` and `traverse` lose the precision the library is built to preserve.
- The guarantee is a property of signature shape, not of the `Result` type.
