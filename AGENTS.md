# AGENTS.md

This file provides guidance to AI agents working with code in this repository.

## Project Structure

Multi-module Gradle project.

| Module                 | Purpose                                                                                       |
|------------------------|-----------------------------------------------------------------------------------------------|
| `graph-guard`          | Core library: `Server` (Bolt proxy), `Bolt` (protocol), `PackStream`, `plugin/Validator`      |
| `graph-guard-validate` | Schema DSL and Cypher validation: `Schema`, `Rule`, `Query`, `Patterns`                       |
| `graph-guard-script`   | `Script` plugin - loads `Server.Plugin` implementations at runtime from scripts               |
| `graph-guard-app`      | CLI application (`CLI` - `@file:JvmName("Main")`, `Inspect`, `Web`) built with Clikt          |
| `graph-guard-utils`    | Internal shared utilities (`Internal.kt`)                                                     |
| `graph-guard-verify`   | Verifies stored graph data against a `Schema` (nodes, relationships, cardinality): `Verifier` |
| `graph-guard-web`      | Kotlin/JS frontend (multiplatform - not JVM)                                                  |

## Build Commands

- `./gradlew test` - run all tests
- `./gradlew :graph-guard:test` - run tests for a specific module
- `./gradlew spotlessApply` - format code (ktfmt + prettier); must run detekt first
- `./gradlew detekt` - lint only
- `./gradlew knit` - regenerate README code examples (run before committing README changes)

## Versions

Managed via `gradle/libs.versions.toml`.

## Project Requirements

- Always use English in code, examples, and comments
- Code isn't just for execution, but also for readability
- Prefer conciseness but not at the expense of clarity
- Only add meaningful comments and tests

## Development Notes

- Public APIs include comprehensive (KDoc) documentation
- Minimize public API surface. Default to internal
- Use @JvmOverloads and @JvmRecord for Java interoperability
- Lifecycle types implement AutoCloseable
- `graph-guard-web` uses `kotlin.multiplatform` - don't apply JVM-only plugins or dependencies to it
- README code examples are managed by `kotlinx-knit`; edit the source in `README.md`, then run
  `./gradlew knit` to regenerate test files under `*/knit/`
- Binary API compatibility is validated via `./gradlew apiCheck`; run `./gradlew apiDump` after
  intentional public API changes

## Code Style

- **Character usage**: only use standard ASCII characters (0-127). Avoid emojis, curly quotes, smart
  quotes, or special UTF-8 symbols
- **No section delimiter comments**: don't use banner/divider comments like `// ---- Section ----`
  or `# -- Section ---`
- **Code comments**: all inline comments should begin with a lowercase letter and not end with
  punctuation
- **Contractions**: use contractions in documentation and comments where natural (e.g. "don't" not
  "do not", "it's" not "it is")
