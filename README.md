# composable-errors

Union-type error composition for Scala 3. Works standalone or alongside ZIO/Cats Effect.

**Define your errors once. Compose freely. Let the compiler track the union.**

## The Problem

Composing operations with different error types requires boilerplate:

```scala
// You have to define a supertype ADT...
sealed trait AppError
case class Validation(e: ValidationError) extends AppError
case class Database(e: DatabaseError) extends AppError

// ...and map errors at every boundary
for {
  input <- validate(raw).left.map(Validation.apply)
  user  <- findUser(input.id).left.map(Database.apply)
} yield user
```

## The Solution

With `composable-errors`, just compose:

```scala
import composable.errors.Result

// NO sealed trait needed
case class ValidationError(field: String, message: String)
case class DatabaseError(query: String, cause: String)

// Error types are tracked automatically via union types
val result: Result[ValidationError | DatabaseError, User] = for {
  input <- validate(raw)      // Result[ValidationError, Input]
  user  <- findUser(input.id) // Result[DatabaseError, User]
} yield user
```

**Zero boilerplate. Zero mapping. Full type safety.**

## Installation

This library is not published to Maven Central. To use it:

```bash
# Clone and publish locally
git clone https://github.com/jpalmerr/composable-errors.git
cd composable-errors
sbt publishLocal
```

Then add to your `build.sbt`:

```scala
libraryDependencies += "dev.composable" %% "composable-errors" % "0.1.0-SNAPSHOT"
```

Requires Scala 3.3+

## Quick Start

### Creating Results

```scala
import composable.errors.Result

// Success
val ok = Result.ok(42)

// Failure
val fail = Result.fail(ValidationError("email", "invalid format"))

// From existing types
val fromEither = Result.fromEither(Right(42))
val fromTry = Result.fromTry(scala.util.Try(42))
val fromOption = Result.fromOption(Some(42), "missing value")

// Catch exceptions
val attempted = Result.attempt(riskyOperation())
```

### Composing with For-Comprehensions

```scala
case class ValidationError(field: String, message: String)
case class DatabaseError(query: String, cause: String)
case class AuthError(userId: String, reason: String)

def validate(input: RawInput): Result[ValidationError, ValidatedInput] = ???
def findUser(id: UserId): Result[DatabaseError, User] = ???
def checkAuth(user: User): Result[AuthError, Unit] = ???

// Compiler automatically tracks: ValidationError | DatabaseError | AuthError
val result = for {
  input <- validate(rawInput)
  user  <- findUser(input.userId)
  _     <- checkAuth(user)
} yield user
```

### Handling Errors

```scala
// Pattern match on the union type
result.fold(
  {
    case ValidationError(field, msg) => BadRequest(s"$field: $msg")
    case DatabaseError(query, cause) => InternalError(cause)
    case AuthError(userId, reason)   => Forbidden(reason)
  },
  user => Ok(user.toJson)
)

// Or use combinators
result
  .recover { case ValidationError(_, _) => defaultUser }
  .getOrElse(fallbackUser)
```

### Integration with ZIO/Cats Effect

```scala
// Convert to Either for interop
val either: Either[ValidationError | DatabaseError, User] = result.toEither

// Use with ZIO
val zio: ZIO[Any, ValidationError | DatabaseError, User] =
  ZIO.fromEither(result.toEither)

// Use with Cats Effect (IO requires Throwable on the left)
val io: IO[User] = IO.fromEither(result.toEither.left.map {
  case e: ValidationError => new RuntimeException(s"Validation: ${e.message}")
  case e: DatabaseError   => new RuntimeException(s"Database: ${e.cause}")
})
```

## API Reference

### Constructors

| Method | Description |
|--------|-------------|
| `Result.ok(a)` | Create a success |
| `Result.fail(e)` | Create a failure |
| `Result.fromEither(e)` | Convert from Either |
| `Result.fromTry(t)` | Convert from Try |
| `Result.fromOption(o, e)` | Convert from Option |
| `Result.attempt(thunk)` | Catch exceptions |

### Operations

| Method | Description |
|--------|-------------|
| `map(f)` | Transform success value |
| `flatMap(f)` | Chain operations (auto-widens error type) |
| `mapError(f)` | Transform error value |
| `fold(onError, onSuccess)` | Handle both cases |
| `getOrElse(default)` | Get value or default |
| `orElse(alternative)` | Try alternative on failure |
| `recover(pf)` | Recover from matching errors |
| `toEither` | Convert to Either |
| `toOption` | Convert to Option (discards error) |

### Utilities

| Method | Description |
|--------|-------------|
| `Result.sequence(list)` | List[Result[E, A]] => Result[E, List[A]] |
| `Result.traverse(list)(f)` | Traverse with fallible function |
| `Result.zip(ra, rb)` | Combine two results into tuple |
| `Result.map2(ra, rb)(f)` | Combine two results with function |

## Known Limitations

1. **Selective type subtraction not supported**: You can't handle one error and have the compiler remove it from the union type. Handle all errors at the boundary.

2. **IDE support is limited**: IntelliJ and Metals have limited support for union types. Error types may show as `Any` in some contexts.

3. **Library evolution**: Adding a new error type to a function doesn't force consumers to update their handlers (unlike sealed traits).

## Write-ups

- [What I Learned Building Error Handling with Scala 3 Union Types](https://gist.github.com/f8685eae583a6ee7a59ef78643ce2a83)

## License

MIT
