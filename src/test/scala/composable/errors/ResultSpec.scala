package composable.errors

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ResultSpec extends AnyFlatSpec with Matchers:

  // Test error types (no common supertype)
  case class ValidationError(field: String, message: String)
  case class DatabaseError(query: String, cause: String)
  case class AuthError(userId: String, reason: String)
  case class NetworkError(endpoint: String, code: Int)
  case class ParseError(input: String)

  // ==========================================
  // Constructor Tests
  // ==========================================

  "Result.ok" should "create a success" in {
    val result = Result.ok(42)
    result.isOk shouldBe true
    result.getOrElse(0) shouldBe 42
  }

  "Result.fail" should "create a failure" in {
    val result = Result.fail(ValidationError("email", "invalid"))
    result.isFail shouldBe true
  }

  "Result.fromEither" should "convert Either to Result" in {
    val right: Either[String, Int] = Right(42)
    val left: Either[String, Int] = Left("error")

    Result.fromEither(right).isOk shouldBe true
    Result.fromEither(left).isFail shouldBe true
  }

  "Result.fromTry" should "convert Try to Result" in {
    import scala.util.{Try, Success, Failure}

    val success = Try(42)
    val failure = Try(throw new RuntimeException("boom"))

    Result.fromTry(success).isOk shouldBe true
    Result.fromTry(failure).isFail shouldBe true
  }

  "Result.attempt" should "catch exceptions" in {
    val success = Result.attempt(42)
    val failure = Result.attempt(throw new RuntimeException("boom"))

    success.isOk shouldBe true
    failure.isFail shouldBe true
  }

  "Result.fromOption" should "convert Option to Result" in {
    val some = Result.fromOption(Some(42), "missing")
    val none = Result.fromOption(None, "missing")

    some.isOk shouldBe true
    none.isFail shouldBe true

    var fallbackEvaluated = false
    Result.fromOption(Some(42), { fallbackEvaluated = true; "missing" }).isOk shouldBe true
    fallbackEvaluated shouldBe false
  }

  // ==========================================
  // Core Operation Tests
  // ==========================================

  "map" should "transform success value" in {
    Result.ok(21).map(_ * 2).getOrElse(0) shouldBe 42
  }

  it should "preserve failure" in {
    val err = ValidationError("x", "y")
    Result.fail(err).map((x: Int) => x * 2).isFail shouldBe true
  }

  "flatMap" should "chain successful operations" in {
    val result = for {
      a <- Result.ok(10)
      b <- Result.ok(20)
    } yield a + b

    result.getOrElse(0) shouldBe 30
  }

  it should "short-circuit on failure" in {
    val failed: Result[ValidationError, Int] = Result.fail(ValidationError("x", "y"))
    var continuationEvaluated = false
    val result = for {
      a <- failed
      b <- { continuationEvaluated = true; Result.ok(20) }
    } yield a + b

    result.isFail shouldBe true
    continuationEvaluated shouldBe false
    result.toEither shouldBe Left(ValidationError("x", "y"))
  }

  "mapError" should "transform error value" in {
    val result = Result.fail("error").mapError(_.toUpperCase)
    result.fold(identity, _ => "") shouldBe "ERROR"
  }

  "fold" should "handle both cases" in {
    Result.ok(42).fold(_ => "error", n => s"success: $n") shouldBe "success: 42"
    Result.fail("oops").fold(e => s"error: $e", _ => "success") shouldBe "error: oops"
  }

  "getOrElse" should "return value on success" in {
    Result.ok(42).getOrElse(0) shouldBe 42

    var defaultEvaluated = false
    Result.ok(42).getOrElse({ defaultEvaluated = true; 0 }) shouldBe 42
    defaultEvaluated shouldBe false
  }

  it should "return default on failure" in {
    Result.fail("error").getOrElse(0) shouldBe 0
  }

  "orElse" should "return original on success" in {
    Result.ok(42).orElse(Result.ok(0)).getOrElse(-1) shouldBe 42

    var alternativeEvaluated = false
    Result.ok(42).orElse({ alternativeEvaluated = true; Result.ok(0) }).getOrElse(-1) shouldBe 42
    alternativeEvaluated shouldBe false
  }

  it should "return alternative on failure" in {
    Result.fail("error").orElse(Result.ok(42)).getOrElse(0) shouldBe 42
  }

  "recover" should "recover from matching errors" in {
    val result = Result.fail("error").recover { case "error" => 42 }
    result.getOrElse(0) shouldBe 42
  }

  it should "pass through non-matching errors" in {
    val result = Result.fail("other").recover { case "error" => 42 }
    result.isFail shouldBe true
    result.toEither shouldBe Left("other")
  }

  it should "evaluate a guarded partial function exactly once when it matches" in {
    var guardEvaluations = 0
    val pf: PartialFunction[String, Int] = {
      case s if { guardEvaluations += 1; s == "boom" } => 42
    }

    Result.fail("boom").recover(pf).getOrElse(0) shouldBe 42
    guardEvaluations shouldBe 1
  }

  it should "evaluate a guarded partial function exactly once when it does not match" in {
    var guardEvaluations = 0
    val pf: PartialFunction[String, Int] = {
      case s if { guardEvaluations += 1; s == "boom" } => 42
    }

    Result.fail("other").recover(pf).toEither shouldBe Left("other")
    guardEvaluations shouldBe 1
  }

  it should "not evaluate the partial function on success" in {
    var guardEvaluations = 0
    val pf: PartialFunction[String, Int] = {
      case _ if { guardEvaluations += 1; true } => 42
    }
    val success: Result[String, Int] = Result.ok(7)

    success.recover(pf).getOrElse(0) shouldBe 7
    guardEvaluations shouldBe 0
  }

  "recoverWith" should "recover from matching errors with a new Result" in {
    val result = Result.fail("boom").recoverWith { case "boom" => Result.ok(42) }
    result.toEither shouldBe Right(42)
  }

  it should "pass through non-matching errors" in {
    val result = Result.fail("other").recoverWith { case "boom" => Result.ok(42) }
    result.toEither shouldBe Left("other")
  }

  it should "surface the replacement error when the recovery itself fails" in {
    val replacement = ValidationError("field", "still bad")
    val result = Result.fail("boom").recoverWith { case "boom" => Result.fail(replacement) }
    result.toEither shouldBe Left(replacement)
  }

  it should "evaluate a guarded partial function exactly once when it matches" in {
    var guardEvaluations = 0
    val pf: PartialFunction[String, Result[Nothing, Int]] = {
      case s if { guardEvaluations += 1; s == "boom" } => Result.ok(42)
    }

    Result.fail("boom").recoverWith(pf).getOrElse(0) shouldBe 42
    guardEvaluations shouldBe 1
  }

  it should "evaluate a guarded partial function exactly once when it does not match" in {
    var guardEvaluations = 0
    val pf: PartialFunction[String, Result[Nothing, Int]] = {
      case s if { guardEvaluations += 1; s == "boom" } => Result.ok(42)
    }

    Result.fail("other").recoverWith(pf).toEither shouldBe Left("other")
    guardEvaluations shouldBe 1
  }

  it should "not evaluate the partial function on success" in {
    var guardEvaluations = 0
    val pf: PartialFunction[String, Result[Nothing, Int]] = {
      case _ if { guardEvaluations += 1; true } => Result.ok(0)
    }
    val success: Result[String, Int] = Result.ok(7)

    success.recoverWith(pf).getOrElse(0) shouldBe 7
    guardEvaluations shouldBe 0
  }

  "toEither" should "convert to Either" in {
    Result.ok(42).toEither shouldBe Right(42)
    Result.fail("error").toEither shouldBe Left("error")
  }

  "toOption" should "convert to Option" in {
    Result.ok(42).toOption shouldBe Some(42)
    Result.fail("error").toOption shouldBe None
  }

  // ==========================================
  // Union Type Widening Tests (THE KEY FEATURE)
  // ==========================================

  "flatMap" should "automatically widen error types to union" in {
    def validate(s: String): Result[ValidationError, Int] =
      if s.nonEmpty then Result.ok(s.length) else Result.fail(ValidationError("input", "empty"))

    def lookup(id: Int): Result[DatabaseError, String] =
      if id > 0 then Result.ok(s"user-$id") else Result.fail(DatabaseError("SELECT", "not found"))

    // The compiler should infer Result[ValidationError | DatabaseError, String]
    val result: Result[ValidationError | DatabaseError, String] = for {
      len  <- validate("hello")
      user <- lookup(len)
    } yield user

    result.isOk shouldBe true
    result.getOrElse("") shouldBe "user-5"
  }

  it should "work with three different error types" in {
    def op1: Result[ValidationError, Int] = Result.ok(1)
    def op2: Result[DatabaseError, Int] = Result.ok(2)
    def op3: Result[AuthError, Int] = Result.ok(3)

    val result: Result[ValidationError | DatabaseError | AuthError, Int] = for {
      a <- op1
      b <- op2
      c <- op3
    } yield a + b + c

    result.getOrElse(0) shouldBe 6
  }

  it should "work with five different error types" in {
    def op1: Result[ValidationError, Int] = Result.ok(1)
    def op2: Result[DatabaseError, Int] = Result.ok(2)
    def op3: Result[AuthError, Int] = Result.ok(3)
    def op4: Result[NetworkError, Int] = Result.ok(4)
    def op5: Result[ParseError, Int] = Result.ok(5)

    val result: Result[ValidationError | DatabaseError | AuthError | NetworkError | ParseError, Int] = for {
      a <- op1
      b <- op2
      c <- op3
      d <- op4
      e <- op5
    } yield a + b + c + d + e

    result.getOrElse(0) shouldBe 15
  }

  it should "infer union type without explicit annotation" in {
    def op1: Result[ValidationError, Int] = Result.ok(1)
    def op2: Result[DatabaseError, Int] = Result.ok(2)

    // No type annotation - compiler should infer
    val result = for {
      a <- op1
      b <- op2
    } yield a + b

    // Verify by assigning to explicitly typed variable
    val check: Result[ValidationError | DatabaseError, Int] = result
    check.getOrElse(0) shouldBe 3
  }

  // ==========================================
  // Pattern Matching on Union Types
  // ==========================================

  "pattern matching" should "work on union error types" in {
    def op1: Result[ValidationError, Int] = Result.fail(ValidationError("email", "invalid"))
    def op2: Result[DatabaseError, Int] = Result.ok(2)

    val result: Result[ValidationError | DatabaseError, Int] = for {
      a <- op1
      b <- op2
    } yield a + b

    val message = result.fold(
      {
        case ValidationError(field, msg) => s"Validation: $field - $msg"
        case DatabaseError(query, cause) => s"Database: $cause"
      },
      n => s"Success: $n"
    )

    message shouldBe "Validation: email - invalid"
  }

  // ==========================================
  // Collection Utilities
  // ==========================================

  "sequence" should "collect all successes" in {
    val results = List(Result.ok(1), Result.ok(2), Result.ok(3))
    Result.sequence(results).getOrElse(Nil) shouldBe List(1, 2, 3)
  }

  it should "fail on first error" in {
    val results = List(Result.ok(1), Result.fail("error"), Result.ok(3))
    Result.sequence(results).isFail shouldBe true
    Result.sequence(results).toEither shouldBe Left("error")

    val twoFailures = List(Result.ok(1), Result.fail("first"), Result.fail("second"))
    Result.sequence(twoFailures).toEither shouldBe Left("first")
  }

  "traverse" should "apply function and collect results" in {
    val result = Result.traverse(List(1, 2, 3))(n => Result.ok(n * 2))
    result.getOrElse(Nil) shouldBe List(2, 4, 6)
  }

  it should "not apply the function to elements after the first failure" in {
    var applied = List.empty[Int]
    val result = Result.traverse(List(1, 2, 3, 4)) { n =>
      applied = applied :+ n
      if n == 2 then Result.fail(ValidationError("n", n.toString)) else Result.ok(n * 2)
    }

    applied shouldBe List(1, 2)
    result.toEither shouldBe Left(ValidationError("n", "2"))
  }

  "zip" should "combine two results" in {
    def op1: Result[ValidationError, Int] = Result.ok(1)
    def op2: Result[DatabaseError, String] = Result.ok("hello")

    val result: Result[ValidationError | DatabaseError, (Int, String)] = Result.zip(op1, op2)
    result.getOrElse((0, "")) shouldBe (1, "hello")
  }

  it should "return the left-hand error when both sides fail" in {
    def op1: Result[ValidationError, Int] = Result.fail(ValidationError("a", "left"))
    def op2: Result[DatabaseError, String] = Result.fail(DatabaseError("q", "right"))

    Result.zip(op1, op2).toEither shouldBe Left(ValidationError("a", "left"))
  }

  "map2" should "combine two results with a function" in {
    def op1: Result[ValidationError, Int] = Result.ok(2)
    def op2: Result[DatabaseError, Int] = Result.ok(3)

    val result: Result[ValidationError | DatabaseError, Int] = Result.map2(op1, op2)(_ * _)
    result.getOrElse(0) shouldBe 6
  }

  it should "return the left-hand error when both sides fail" in {
    def op1: Result[ValidationError, Int] = Result.fail(ValidationError("a", "left"))
    def op2: Result[DatabaseError, Int] = Result.fail(DatabaseError("q", "right"))

    Result.map2(op1, op2)(_ * _).toEither shouldBe Left(ValidationError("a", "left"))
  }

  // ==========================================
  // Side Effect Utilities
  // ==========================================

  "tap" should "apply side effect on success" in {
    var called = false
    Result.ok(42).tap(_ => called = true)
    called shouldBe true
  }

  it should "not apply side effect on failure" in {
    var called = false
    Result.fail("error").tap((_: Int) => called = true)
    called shouldBe false
  }

  "tapError" should "apply side effect on failure" in {
    var called = false
    Result.fail("error").tapError(_ => called = true)
    called shouldBe true
  }

  it should "not apply side effect on success" in {
    var called = false
    Result.ok(42).tapError((_: String) => called = true)
    called shouldBe false
  }

  // ==========================================
  // Compile-Time Type Safety Tests
  // ==========================================

  "type safety" should "accept correct union type annotation" in {
    assertCompiles("""
      import composable.errors.Result
      case class ErrA(msg: String)
      case class ErrB(msg: String)
      def op1: Result[ErrA, Int] = Result.ok(1)
      def op2: Result[ErrB, Int] = Result.ok(2)
      val result: Result[ErrA | ErrB, Int] = for {
        a <- op1
        b <- op2
      } yield a + b
    """)
  }

  it should "reject assigning union result to wrong single error type" in {
    assertDoesNotCompile("""
      import composable.errors.Result
      case class ErrA(msg: String)
      case class ErrB(msg: String)
      case class ErrC(msg: String)
      def op1: Result[ErrA, Int] = Result.ok(1)
      def op2: Result[ErrB, Int] = Result.ok(2)
      val result: Result[ErrC, Int] = for {
        a <- op1
        b <- op2
      } yield a + b
    """)
  }

  it should "reject assigning union result to incomplete union" in {
    assertDoesNotCompile("""
      import composable.errors.Result
      case class ErrA(msg: String)
      case class ErrB(msg: String)
      def op1: Result[ErrA, Int] = Result.ok(1)
      def op2: Result[ErrB, Int] = Result.ok(2)
      val result: Result[ErrA, Int] = for {
        a <- op1
        b <- op2
      } yield a + b
    """)
  }

  it should "accept supertype annotation for union members" in {
    assertCompiles("""
      import composable.errors.Result
      case class ErrA(msg: String) extends Exception
      case class ErrB(msg: String) extends Exception
      def op1: Result[ErrA, Int] = Result.ok(1)
      def op2: Result[ErrB, Int] = Result.ok(2)
      val result: Result[Exception, Int] = for {
        a <- op1
        b <- op2
      } yield a + b
    """)
  }
