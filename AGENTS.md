# AGENTS.md

Guidance for coding agents (and humans) working on bikesched: a PostgreSQL-backed job scheduler
library for Java 26 with a Kotlin companion module. Read `README.md` for what the library does;
this file covers how to change it.

## Build and run

- Java 26 toolchain (Gradle provisions it if missing), Docker for the tests (Testcontainers,
  `postgres:18`). Gradle wrapper only: `./gradlew build`.
- Formatting is enforced by Spotless in `check`: run `./gradlew spotlessApply` after editing,
  before `build`. Java uses google-java-format (2-space indent, 100 columns), Kotlin ktfmt in
  Google style. Write new code in that style so patches match.
- Compiler settings fail on warnings: Java `-Xlint:all -Werror`, Kotlin `allWarningsAsErrors` and
  explicit API mode (`public` and return types on every public declaration). Javadoc runs with
  `-Werror` too; also check the build log for warnings, not only failures.
- Local database: `docker compose up -d` (prefix `POSTGRES_PORT=5433` if 5432 is taken), then
  `./gradlew :bikesched-core:runStarter` (dev `Starter` in test sources) or
  `./gradlew :bikesched-examples:run`.
- Every version (libraries, Gradle plugins, Java, the project version) lives in
  `gradle/libs.versions.toml`; no version literals in `.kts` files. Use the latest stable releases.

## Layout

| Module | Contents |
|---|---|
| `bikesched-core` | the library; runtime dependencies: PostgreSQL JDBC driver and `slf4j-api` only |
| `bikesched-kotlin` | Kotlin extensions on top of the core API; depends on core and the Kotlin stdlib only |
| `bikesched-examples` | runnable examples (one package per scenario, each with a Testcontainers test); may use Jackson etc. |
| `build-logic` | convention plugins: `bikesched.java-conventions`, `bikesched.java-library`, `bikesched.kotlin-library` |

Core packages (JPMS module `bikesched.core`, see `module-info.java`):

- `bikesched.core`: entry point `Bikesched` + `BikeschedBuilder`, exceptions, SPIs (`JsonMapper`,
  `RetryBackoff`), `BikeschedLogContext` (MDC keys).
- `bikesched.core.job`, `bikesched.core.event`: public API, interfaces/records/enums/exceptions only.
- `bikesched.core.internal` (+ `.schema`): every implementation (`Default<X>`), not exported.

New dependencies for core need a very good reason: integrations users may want to swap become a
small SPI with a dependency-free default; concrete libraries are test dependencies only.

## API conventions

- Callback interfaces are `<X>Handler` with one `handle(...)` method (`JobHandler`, `StepHandler`,
  `EventHandler`, `TimeoutHandler`, `CompensationHandler`); they receive the context object (e.g.
  the `Step`), not just a value.
- Everything handed to user code (job, event, builders, `JobFailure`, `EventWait`) is a public
  **interface**; implementations are `Default<X>` in `internal`.
- Builders: one fluent path ending in a verb that performs the action (`newJob(type)…schedule()`,
  `newEvent(name, key)…publish()`, `newStep(name, Class)…run(handler)`). Mutually exclusive options
  are separate builder types (`connection(…)` → `TransactionalJobBuilder`, `recurring(…)` →
  `RecurringJobBuilder`), enforced at compile time, not with runtime checks.
- Boolean accessors use the `is` prefix (`isPolling`, `isEnded`, `isFixedRate`); builder setters are
  named after the property (`nodeName(String)`).
- SPI defaults are static factories on the interface (`JsonMapper.rawJson()`, `RetryBackoff.none()`).
- Results get their own type in the feature package (`event.ReceivedEvent`); lookups return
  `Optional` of a minimal interface (`findJob` → `JobInfo` with a `JobState` enum).
- No exceptions for control flow: intent is a flag checked after the handler (`job.cancel()`,
  `job.fail()`), except suspending at an event wait (internal `Error`, see `JobSuspended`).
- New mechanisms are opt-in: jobs not using them behave exactly as before (no extra state, replay
  or round-trip).
- Breaking API changes are fine while unreleased; rename across code, tests, examples and README in
  one go.

### Kotlin module (`bikesched.kotlin`)

- Kotlin-style entry points are extensions on `Bikesched` with named arguments and an optional
  trailing handler, implemented with the Java builders underneath: `handleJobs<T>(type) { payload -> }`
  (the `Job` is the receiver), `createJob(…)`, `createRecurringJob(…)`, `publishEvent(…)`, and in
  handlers `step<T>(name, compensation = …) { }` and `waitForEvent<T>(step, name, key, timeout) { }`.
- Reified helpers do the `as T` cast inline, so a non-null `T` with a `null` value throws. `Unit`
  steps run as `Void` steps (no `Unit` reaches the `JsonMapper`).
- Use distinct names rather than overloading a Java member: a member always wins over an extension
  with the same signature (hence `findJobOrNull`, `node.idOrNull`, `handleJobs`).
- Keep one Kotlin style per operation; don't add parallel builder-ish extensions.

## Database

- All tables are prefixed `bikesched_`. Migrations are plain SQL in
  `bikesched-core/src/main/resources/bikesched/migrations`, applied by the own `SchemaMigrator`
  (no Flyway/Liquibase). While unreleased, schema changes go **into `V001` in place**; local
  databases then need `docker compose down -v` (checksum mismatch).
- New states go into the table's CHECK constraint. History rows are never deleted on state
  changes; they get a terminal state (`CANCELLED`, `COMPENSATED`) other code paths ignore.
- A new table also goes into `SchemaMigratorTest` (drop list and expected tables) and the README's
  Database section.
- JDBC only inside the stores (`JobStore`, `StepStore`, `EventStore`, `NodeStore`), which receive a
  `DatabaseAccess` (no reference to `Database`, which only wires them). Statements go through the
  `Sql` handle: `one`/`list`/`update`/`execute`, `Sql.Row` getters, `Sql.millis/seconds/timestamp/
  typed` for durations and typed nulls. No `PreparedStatement`, `ResultSet` or
  `catch (SQLException)` in stores.
- `access.withConnection(db -> …, e -> new BikeschedException("… failed", e))`, `inTransaction`,
  `inReadOnlyTransaction`, `onCallerConnection(connection, …)`: the last argument is a lazy
  exception handler, called only for an `SQLException`. A caller's connection is never committed,
  rolled back, closed or switched to auto-commit.
- `Sql.one()` rejects a `null` mapping: map nullable columns into a record so "no row" and "null
  value" stay distinct.

## Tests

- One focused test class per feature area. Integration tests extend `AbstractBikeschedTest`
  (Bikesched level) or `AbstractStoreTest` (store level); both share **one** Postgres container per
  JVM. Scope every assertion to a per-test job type or node name, and delete rows that would stay
  claimable (future jobs, recurring occurrences) after the test.
- Wait for async outcomes with Awaitility (`await().untilAsserted { … }`, defaults set in the base
  classes); "nothing happens" checks are `Thread.sleep` + assert.
- Log assertions use `LogCapture.of(X.class)`; log meaningful lifecycle steps and assert them.
- Core tests use `JsonMapper.rawJson()`: payloads and step results must be valid JSON text
  (`"\"x\""`, not `"x"`).
- Kotlin tests share `TestDatabase` and map data classes with Jackson's Kotlin module; use
  `unique(name)` for job types and event names, computed outside handlers (handlers replay).
- Verify with `./gradlew build` and count results from `build/test-results/test/*.xml`; rerun
  timing-sensitive tests a few times with `--tests … --rerun`.

## Documentation

- Keep `README.md` in sync in the same change: feature sections with samples, the builder option
  table (defaults checked against the code), the Kotlin section, and every table in the Database
  section. Samples are not compiled, so check them against the API.
- Public Javadoc must not link to non-exported classes; exceptions need `@Serial` ids and comments
  on serialized fields.

## Commits

Small messages: one subject line plus a few `-` bullets of user-visible changes since the last
commit. Push only as a fast-forward.
