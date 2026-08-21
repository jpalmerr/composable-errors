# composable-errors

Scala 3 library that uses union types to eliminate sealed trait boilerplate for error handling. `flatMap` widens `Result[E, A]` to `Result[E | E2, B]` automatically.

## Commands

| Task | Command |
|------|---------|
| Compile | `sbt compile` |
| Test (all) | `sbt test` |
| Test (single) | `sbt "testOnly composable.errors.ResultSpec"` |
| Publish local | `sbt publishLocal` |
| Clean | `sbt clean` |

For faster iteration, run `sbt` to enter the shell, then run commands without the `sbt` prefix (avoids repeated JVM startup).

## Architecture

```
src/
  main/scala/composable/errors/
    Result.scala          -- Entire library (opaque type + extension methods)
  test/scala/composable/errors/
    ResultSpec.scala       -- Full test suite (ScalaTest FlatSpec)
build.sbt                  -- Scala 3.3.1, org "dev.composable", version 0.1.0-SNAPSHOT
project/build.properties   -- sbt 1.10.11
AGENTS.md                  -- Detailed project context, design decisions, roadmap
gist-union-type-error-widening.md -- Write-up on union type findings
```

Single-file library, single-file test suite. No subprojects.

## Core Design

```scala
opaque type Result[+E, +A] = Either[E, A]

// The key trick: flatMap widens the error union
def flatMap[E2, B](f: A => Result[E2, B]): Result[E | E2, B]
```

Zero runtime overhead -- compiles to `Either` at JVM level. Extension methods provide the API surface.

## Code Style

- Scala 3 syntax: significant indentation, `extension`, `opaque type`
- Zero production dependencies (only ScalaTest for tests)
- Compiler flags: `-deprecation -feature -unchecked -Xfatal-warnings`
- ScalaTest FlatSpec with Matchers
- Test error types are intentionally unrelated (no common supertype) -- that is the point of the library

## Gotchas

- **`-Xfatal-warnings` is on**: all warnings are compilation errors. Do not introduce deprecation warnings or unused imports.
- **`toOption` calls `self.toOption`** (line 125 of Result.scala): this delegates to `Either.toOption` through the opaque type. Not a recursive call despite looking like one.
- **No type subtraction**: Scala 3 cannot compute `(A | B) - A`. You cannot handle one error and narrow the union. All errors must be handled at boundaries.
- **IDE shows `Any`**: IntelliJ/Metals sometimes display union types as `Any`. The code compiles correctly regardless.
- **Exhaustiveness not enforced**: unlike sealed traits, adding a new error type to a function signature does not force consumers to update their pattern matches.
- **`.claude/` is gitignored**: spike research, plans, and local settings live there. Not part of the published project.

## Project Status

Phase 1 (core library) is complete. Phases 2-5 (interop modules, full docs, publishing, community feedback) are not started. See `AGENTS.md` for the full roadmap.
