# composable-errors - Project Context

## What This Is

A Scala 3 library that eliminates supertype ADT boilerplate for error handling using **union types**. The core insight: Scala 3's union types can replace the sealed trait hierarchies that teams write to compose operations with different error types.

```scala
// BEFORE: sealed trait + leftMap at every boundary
sealed trait AppError
case class Validation(e: ValidationError) extends AppError
case class Database(e: DatabaseError) extends AppError

for {
  input <- validate(raw).left.map(Validation.apply)
  user  <- findUser(input.id).left.map(Database.apply)
} yield user

// AFTER: just compose - compiler tracks the union
val result: Result[ValidationError | DatabaseError, User] = for {
  input <- validate(raw)      // Result[ValidationError, Input]
  user  <- findUser(input.id) // Result[DatabaseError, User]
} yield user
```

## Author Context

James Palmer. 7 years Scala. FP advocate, ZIO at work (Conduktor). This is a personal/portfolio project demonstrating Scala 3 type system mastery. Give direct feedback, challenge framing, push back on overcomplicated ideas.

## Architecture

### Core Type

```scala
opaque type Result[+E, +A] = Either[E, A]
```

**Why opaque type over case class**: Zero runtime overhead. `Result` compiles directly to `Either` at the JVM level - no wrapper allocations. Extension methods provide the API.

**The key operation** - `flatMap` widens the error type:

```scala
def flatMap[E2, B](f: A => Result[E2, B]): Result[E | E2, B]
```

When for-comprehensions desugar, the compiler automatically computes `E1 | E2 | E3 | ...` without type annotations.

### API Surface (complete)

**Constructors**: `ok`, `fail`, `fromEither`, `fromTry`, `attempt`, `fromOption`
**Core ops**: `flatMap` (auto-widens), `map`, `mapError`, `fold`, `getOrElse`, `orElse`, `recover`, `recoverWith`
**Interop**: `toEither`, `toOption`
**Introspection**: `isOk`, `isFail`
**Side effects**: `tap`, `tapError`
**Collection**: `sequence`, `traverse`, `zip`, `map2`

Zero production dependencies. Only ScalaTest for testing.

### Key Files

| File | Purpose |
|------|---------|
| `src/main/scala/composable/errors/Result.scala` | Entire library (186 lines) |
| `src/test/scala/composable/errors/ResultSpec.scala` | Full test suite (296 lines) |
| `build.sbt` | Scala 3.3.1, org `dev.composable`, version 0.1.0-SNAPSHOT |
| `README.md` | User-facing docs with examples and API reference |

### Research Artifacts (`.Codex/` - gitignored)

The design was validated through a structured spike investigation with adversarial challenge phase. These files capture the reasoning:

| File | What It Contains |
|------|-----------------|
| `.Codex/spikes/composable-errors/output/adr.md` | Architecture Decision Record (APPROVED) |
| `.Codex/spikes/composable-errors/output/synthesis.md` | Final recommendation and confidence ratings |
| `.Codex/spikes/composable-errors/branches/` | 4 explored design approaches (A-D) |
| `.Codex/spikes/composable-errors/challenges/` | Adversarial challenges and test results |
| `.Codex/plans/composable-errors-v1.md` | 5-phase implementation plan |

## Design Decisions (and Why)

### Branch A chosen over 3 alternatives

| Branch | Approach | Why Not |
|--------|----------|---------|
| **A (chosen)** | Opaque `Result[E, A]` with union widening in flatMap | -- |
| B | Capability-based `CanFail` with context functions | Unfamiliar API, lower adoption potential |
| C | Direct-style with `boundary`/`break` | Less type-safe |
| D | Macro-based type subtraction | Complexity outweighs benefit |

### What was deliberately excluded from v1

- **`handleTyped` (type-subtracting handler)**: Required `asInstanceOf` - unsafe. Removed.
- **Accumulation/Validation**: Use Cats Validated for that. Result is fail-fast.
- **Deep effect system integration**: Just boundary conversions via `toEither`.
- **CanFail capability API**: Consider for v1.1 if requested.

### Known Limitations (documented in README)

1. **No type subtraction**: Can't handle one error type and have the compiler remove it from the union. Scala 3 doesn't support `E - E1`. Handle all errors at boundaries.
2. **IDE support limited**: IntelliJ and Metals show union types as `Any` in some contexts. Code compiles correctly regardless.
3. **Library evolution**: Adding a new error type to a function signature doesn't force consumers to update their pattern matches (unlike sealed traits with exhaustiveness checking).

## Current State

**Phase 1 (Core Library): COMPLETE**
- All source code written and tested
- 30+ test cases including union widening with 2, 3, and 5 error types
- All tests pass (`sbt test`)
- Compiler flags: `-deprecation -feature -unchecked -Xfatal-warnings`

**What's left (Phases 2-5)**:

| Phase | Status | Description |
|-------|--------|-------------|
| 2: Interop modules | Not started | `composable-errors-zio` and `composable-errors-cats` as separate sbt subprojects |
| 3: Documentation | Partially done | README exists; examples, migration guide, optional mdoc site still needed |
| 4: Publishing | Not started | sbt-ci-release, GitHub Actions CI/CD, Sonatype/Maven Central |
| 5: Feedback | Not started | Community validation, IDE testing with real users |

**Critical path**: Phase 3 (finish docs) -> Phase 4 (publish) -> Phase 5 (feedback). Interop can ship separately.

## Positioning

**vs Kyo's Abort effect**: Kyo is a full effect system. composable-errors is a lightweight standalone library for teams who just want better Either, or who use ZIO/Cats and want cleaner error composition at the domain layer.

> "Result[E, A] is to Either what union types are to sealed traits - less boilerplate, same semantics."

**Target audience**: Teams not ready for full effect systems, or teams already using ZIO/Cats who want union-type error composition in their domain logic without sealed trait hierarchies.

## Confidence Assessment

From the spike synthesis:
- **Core technical approach**: 9/10 (prototyped and validated)
- **Strategic positioning**: 6/10 (Kyo overlap, IDE concerns)
- **Overall**: 7.5/10 - CONDITIONAL GO

## Working With This Codebase

```bash
sbt compile    # Build
sbt test       # Run all tests
```

The entire library is one file. The entire test suite is one file. There is no complexity to navigate.

When making changes:
- Scala 3 syntax throughout (significant indentation, `extension`, `opaque type`)
- ScalaTest FlatSpec style for tests
- Test error types intentionally have no common supertype - that's the point
- Keep zero production dependencies
