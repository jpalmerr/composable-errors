package composable.errors

import scala.util.{Try, Success, Failure}
import scala.util.control.NonFatal

/**
 * A Result type that automatically widens error types using Scala 3 union types.
 *
 * Eliminates the need for supertype ADT hierarchies when composing operations
 * with different error types:
 *
 * {{{
 * // NO sealed trait needed - just compose
 * val result: Result[ValidationError | DatabaseError, User] = for {
 *   input <- validate(raw)      // Result[ValidationError, Input]
 *   user  <- findUser(input.id) // Result[DatabaseError, User]
 * } yield user
 * }}}
 *
 * Works standalone or alongside ZIO/Cats Effect via `.toEither`.
 */
opaque type Result[+E, +A] = Either[E, A]

object Result:

  // ==========================================
  // Constructors
  // ==========================================

  /** Create a successful Result. */
  def ok[A](a: A): Result[Nothing, A] = Right(a)

  /** Create a failed Result. */
  def fail[E](e: E): Result[E, Nothing] = Left(e)

  /** Convert an Either to a Result. */
  def fromEither[E, A](either: Either[E, A]): Result[E, A] = either

  /** Convert a Try to a Result. */
  def fromTry[A](t: Try[A]): Result[Throwable, A] = t match
    case Success(a) => Right(a)
    case Failure(e) => Left(e)

  /** Catch exceptions from a thunk. */
  def attempt[A](thunk: => A): Result[Throwable, A] =
    try Right(thunk)
    catch case NonFatal(e) => Left(e)

  /** Create a Result from an Option, using the provided error if None. */
  def fromOption[E, A](option: Option[A], ifNone: => E): Result[E, A] =
    option match
      case Some(a) => Right(a)
      case None    => Left(ifNone)

  // ==========================================
  // Extension Methods
  // ==========================================

  extension [E, A](self: Result[E, A])

    /**
     * The key operation: flatMap widens the error type to E | E2.
     *
     * This allows composing operations with different error types
     * without manually mapping to a common supertype.
     */
    def flatMap[E2, B](f: A => Result[E2, B]): Result[E | E2, B] =
      self match
        case Left(e)  => Left(e)
        case Right(a) => f(a)

    /** Transform the success value. */
    def map[B](f: A => B): Result[E, B] =
      self match
        case Left(e)  => Left(e)
        case Right(a) => Right(f(a))

    /** Transform the error value. */
    def mapError[E2](f: E => E2): Result[E2, A] =
      self match
        case Left(e)  => Left(f(e))
        case Right(a) => Right(a)

    /** Fold over both cases. */
    def fold[B](onError: E => B, onSuccess: A => B): B =
      self match
        case Left(e)  => onError(e)
        case Right(a) => onSuccess(a)

    /** Get the success value or a default. */
    def getOrElse[A1 >: A](default: => A1): A1 =
      self match
        case Left(_)  => default
        case Right(a) => a

    /** Try an alternative if this Result failed. */
    def orElse[E2, A1 >: A](alternative: => Result[E2, A1]): Result[E2, A1] =
      self match
        case Left(_)  => alternative
        case Right(a) => Right(a)

    /**
     * Recover from errors using a partial function.
     * Unmatched errors pass through unchanged.
     */
    def recover[A1 >: A](pf: PartialFunction[E, A1]): Result[E, A1] =
      self match
        // lift dispatches through a single applyOrElse, so a guarded pf is tested once.
        case Left(e) =>
          pf.lift(e) match
            case Some(a) => Right(a)
            case None    => Left(e)
        case other => other

    /**
     * Recover from errors using a partial function that returns a Result.
     * Unmatched errors pass through unchanged.
     */
    def recoverWith[E2, A1 >: A](pf: PartialFunction[E, Result[E2, A1]]): Result[E | E2, A1] =
      self match
        case Left(e) =>
          pf.lift(e) match
            case Some(recovered) => recovered
            case None            => Left(e)
        case Right(a) => Right(a)

    /** Convert to Either. */
    def toEither: Either[E, A] = self

    /** Convert to Option, discarding the error. */
    def toOption: Option[A] = self.toOption

    /** Check if this is a success. */
    def isOk: Boolean = self.isRight

    /** Check if this is a failure. */
    def isFail: Boolean = self.isLeft

    /** Apply a side effect on success. */
    def tap(f: A => Unit): Result[E, A] =
      self match
        case Right(a) => f(a); self
        case _ => self

    /** Apply a side effect on failure. */
    def tapError(f: E => Unit): Result[E, A] =
      self match
        case Left(e) => f(e); self
        case _ => self

  // ==========================================
  // Collection Utilities
  // ==========================================

  /**
   * Sequence a list of Results into a Result of a list.
   * Fails with the first error encountered.
   */
  def sequence[E, A](results: List[Result[E, A]]): Result[E, List[A]] =
    results.foldRight[Result[E, List[A]]](ok(List.empty[A])) { (ra, acc) =>
      for
        a  <- ra
        as <- acc
      yield a :: as
    }

  /**
   * Traverse a list with a function that may fail.
   * Fails with the first error encountered, and does not apply `f` to any element after it.
   */
  def traverse[E, A, B](as: List[A])(f: A => Result[E, B]): Result[E, List[B]] =
    as.foldLeft[Result[E, List[B]]](ok(List.empty[B])) { (acc, a) =>
      acc.flatMap(bs => f(a).map(_ :: bs))
    }.map(_.reverse)

  /**
   * Combine two Results into a tuple.
   * Fails with the first error encountered.
   */
  def zip[E1, E2, A, B](ra: Result[E1, A], rb: Result[E2, B]): Result[E1 | E2, (A, B)] =
    for
      a <- ra
      b <- rb
    yield (a, b)

  /**
   * Combine two Results using a function.
   * Fails with the first error encountered.
   */
  def map2[E1, E2, A, B, C](ra: Result[E1, A], rb: Result[E2, B])(f: (A, B) => C): Result[E1 | E2, C] =
    for
      a <- ra
      b <- rb
    yield f(a, b)
