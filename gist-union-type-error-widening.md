# What I Learned Building Error Handling with Scala 3 Union Types

---

## The Problem

Every Scala service I've worked on that chooses to model errors writes the same error handling boilerplate:

```scala
// create a sealed trait hierarchy
sealed trait AppError
case class ValidationFailed(e: ValidationError) extends AppError
case class DatabaseFailed(e: DatabaseError) extends AppError
case class NetworkFailed(e: NetworkError) extends AppError

// constantly map errors at composition boundaries
for {
  input <- validate(raw).left.map(ValidationFailed.apply)
  user  <- findUser(input.id).left.map(DatabaseFailed.apply)
  data  <- fetchRemote(user).left.map(NetworkFailed.apply)
} yield data
```

This pattern exists *even with ZIO and Cats Effect*, particularly ZIO which encourages modeling the error channel.
You're still writing wrapper cases and `leftMap` calls at every boundary.

The boilerplate costs:
- Every new error type requires a wrapper case in the ADT
- Every function composition needs explicit `leftMap`
- The sealed trait hierarchy grows linearly with features
- Refactoring error hierarchies is tedious

---

## The Inspo

Inspired by Unison's direct style and composable errors, I wanted to try something composable in Scala.    

Scala 3 union types can replace the boilerplate entirely. The trick: if `flatMap` is defined to return `Result[E1 | E2, B]` instead of `Result[E, B]`, the compiler automatically computes the union of all error types flowing through a **for comprehension**.

Core definition:

```scala
opaque type Result[+E, +A] = Either[E, A]

extension [E, A](self: Result[E, A])
  def flatMap[E2, B](f: A => Result[E2, B]): Result[E | E2, B] =
    self match
      case Left(e)  => Left(e)
      case Right(a) => f(a)
```

Now composition requires zero boilerplate:

```scala
val result: Result[ValidationError | DatabaseError | NetworkError, Data] = for {
  input <- validate(raw)      // Result[ValidationError, Input]
  user  <- findUser(input.id) // Result[DatabaseError, User]
  data  <- fetchRemote(user)  // Result[NetworkError, Data]
} yield data
```

No explicit type annotation needed on `result`. The compiler infers `ValidationError | DatabaseError | NetworkError` automatically as the for comprehension "desugars". The `flatMap` calls chain together, each widening the union:

- After line 1: `Result[ValidationError, Input]`
- After line 2: `Result[ValidationError | DatabaseError, User]`
- After line 3: `Result[ValidationError | DatabaseError | NetworkError, Data]`

---

## What Works Well

**Type inference is automatic.** No manual union construction, no `|` operators in user code. We van lean on the compiler for this.

**Pattern matching on unions works at runtime.** This was my biggest concern. It works perfectly:

```scala
result match
  case Left(e: ValidationError) => println(s"Validation failed: $e")
  case Left(e: DatabaseError) => println(s"Database failed: $e")
  case Left(e: NetworkError) => println(s"Network failed: $e")
  case Right(data) => println(s"Success: $data")
```

The JVM's runtime type information handles the distinction. All three error types are preserved in the union.

**It scales.** Try with 10+ error types in a single comprehension. No compiler performance issues. No type inference timeouts. We're leaning on Scala 3 existing tooling.

**Union types flow through generics.** If you pass a `Result[E, A]` through an `identity` function or store it in a container, the union type is preserved. No erasure surprises.

**Same erasure types are distinguishable.** Two generic error cases like `ApiError[String]` and `ApiError[Int]` remain distinct in the union and can be pattern-matched separately.

**Zero runtime overhead.** The `opaque type` compiles directly to `Either` at the JVM level. 

---

## Where It Breaks Down

### No Type Subtraction

Scala 3 has no operator to compute `(A | B | C) - A = (B | C)`. This is a real constraint.

It means you can't write a handler that deals with one error type and asks the compiler to narrow the remaining union:

```scala
// This doesn't work — compiler can't compute E - ValidationError
def handleValidation[E, A](result: Result[E | ValidationError, A]): Result[E, A] =
  result match
    case Left(e: ValidationError) => Left(???) // E is unknown
    case Left(e) => Left(e)
    case Right(a) => Right(a)
```

With sealed traits, exhaustiveness checking guides you to handle every case. With unions, the compiler can't tell you "you forgot to handle NetworkError" — it just tracks that the union contains it.

This means you must handle ALL errors at the boundary. You can't peel off errors one at a time in a composable way.


### Exhaustiveness

With sealed traits, adding a new error case forces recompilation and exhaustiveness errors everywhere the match isn't complete. We are *forced* to handle the new case.

With union types, adding a new error type to a function's return signature silently widens the union at call sites. Consumers' code still compiles. They aren't forced to update their error handlers.

Exhaustiveness is a big miss.

---

## Conclusion

Union types are excellent for error *accumulation at the type level*. The compiler tracks what can go wrong without sealed trait boilerplate.

But sealed traits still win when you need to *handle* errors individually and want the compiler to enforce completeness.
And if you don't need to handle error types individually, why type them at all?

There are three levels of error handling granularity:
1. Untyped (Throwable) — errors are an implementation detail, not an architectural concern
   - MonadError gives you composable, selective handling via `recoverWith`/`handleErrorWith` — peel off one error at a time, let the rest propagate
   - Tagless final completes this: algebras don't mention errors at all, implementations raise into `F`, and error types stay at the edges. This is the pattern that actually eliminated sealed trait boilerplate in production for me
2. Union-typed — you track what can go wrong, handle selectively at
   boundaries
3. ADT-typed — you handle every case, compiler enforces completeness

Most codebases need (1) and (3). Union types serve (2), which is a real but narrower use case than I anticipated.

---

**Repo**: [composable-errors on GitHub](https://github.com/jpalmer/composable-errors)
