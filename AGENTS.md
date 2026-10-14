# AGENTS.md

Guidance for coding agents (and humans) working on bikesched: a PostgreSQL-backed job scheduler
library for Java 26 with a Kotlin companion module. Read `README.md` and `docs/` for what the library
does; this file covers how to change it.

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
  run an example, e.g. `./gradlew :bikesched-examples:java:runSimple`.
- Source files start with `// SPDX-License-Identifier: Apache-2.0`; Spotless adds it (and
  `spotlessCheck` fails without it). The license is Apache 2.0 (`LICENSE`, `NOTICE`); the name
  and logo are covered by `TRADEMARKS.md` (ADR 0021).
- Every version (libraries, Gradle plugins, Java, the project version) lives in
  `gradle/libs.versions.toml`; no version literals in `.kts` files. Use the latest stable releases.
- CI: `.github/workflows/build.yml` runs `./gradlew build` on every pull request and push to
  `main` (Temurin JDK, Docker for Testcontainers), except when only Markdown files and SVG images
  changed (a `changes` job skips `build`, which still reports success for required checks); a separate job publishes snapshot versions of the
  library modules to GitHub Packages after successful builds of `main` only (`publishAllPublicationsToGitHubPackagesRepository`, convention plugin
  `bikesched.publishing` with `maven-publish`). Keep its `java-version` in sync with `java` in the
  version catalog.

## Layout

| Module | Contents |
|---|---|
| `bikesched-core` | the library; runtime dependencies: PostgreSQL JDBC driver and `slf4j-api` only, plus the annotations-only `jspecify` (`api`) |
| `bikesched-jackson` | `JacksonJsonMapper` (Java only, package `org.bikesched.jackson`); depends on core and `jackson-databind` (both `api`) |
| `bikesched-kotlin` | Kotlin extensions on top of the core API; depends on core and the Kotlin stdlib only (tests use bikesched-jackson) |
| `bikesched-annotations` | `@JobHandler` and `JobHandlers` (Java only, package `org.bikesched.annotation`): job handlers declared as annotated methods, invoked via `MethodHandle`; depends on core only (`api`; tests use bikesched-jackson) |
| `bikesched-cron` | `CronRecurrence` and `CronRecurrenceParser` (Java only, package `org.bikesched.cron`): cron expressions parsed with cron-utils; depends on core and `cron-utils` only (both `api`, cron-utils is an automatic module; tests use bikesched-annotations) |
| `bikesched-micrometer` | `MicrometerJobListener` and `MicrometerEventListener` (Java only, package `org.bikesched.micrometer`): meters from the `JobListener` and `EventListener` SPIs; depends on core and `micrometer-core` (both `api`; Micrometer is an automatic module, hence the suppressed `requires-automatic` lints in `module-info.java`) |
| `bikesched-spring-boot-starter` | Spring Boot 4 auto-configuration (Java only, package `org.bikesched.spring`): builds the node from the context's `DataSource` and the `bikesched.*` properties, discovers `@JobHandler`/`JobTypeHandler` beans (proxy-safe via `JobHandlers.scan(Class)`/`bindTo`), polls with a `SmartLifecycle`, optional Jackson/Micrometer wiring (both `compileOnly`), recurrence parsers: ISO-8601 durations, Spring's six-field `CronExpression` and any `RecurrenceParser` beans (bikesched-cron's is not auto-configured); see `docs/spring.md`; Spring artifacts are automatic modules, hence the suppressed lints in `module-info.java` |
| `bikesched-admin` | the admin UI (Java only, package `org.bikesched.admin.web`): `BikeschedAdmin` serves a page and a JSON API through a framework-neutral `handle(AdminRequest)` and a JDK `com.sun.net.httpserver` adapter, `AdminAccess` in the subpackage `access` decides per request (open by default, built-in Basic authentication); the page is static files in `src/main/resources/org/bikesched/admin/web/ui/` (ES modules with Preact and htm, copied from their WebJars by the `vendorUi` task, no build step, CSP without inline scripts or styles); depends on core only (`api`); see `docs/admin.md` |
| `bikesched-examples` | runnable examples, described in its own `README.md`, in two submodules: `java` (plain Java examples, one package per scenario, each with a Testcontainers test using the shared `util.Database`; JSON via bikesched-jackson) and `spring` (the Spring Boot example as a regular Boot application with the Spring Boot Gradle plugin, `bootRun`) |
| `bikesched-website` | the project site at `bikesched.org`, generated with Hugo from `README.md` and `docs/` (Hugo module mounts, no content copy) and the Hextra theme (git submodule), described in its own `docs/specs/website-project-site.md` and [ADR 0035](docs/decisions/0035-website-hosted-on-static-app.md); built with `./gradlew :bikesched-website:hugoBuild`, deployed to static.app by `.github/workflows/website.yml` |
| `build-logic` | convention plugins: `bikesched.java-conventions`, `bikesched.java-library`, `bikesched.kotlin-library` |

Core packages (JPMS module `org.bikesched`, see `module-info.java`):

- `org.bikesched`: entry point `Bikesched` + `BikeschedBuilder`, exceptions, SPIs (`JsonMapper`,
  `RetryBackoff`), `BikeschedLogContext` (MDC keys).
- `org.bikesched.job`, `org.bikesched.job.listener`, `org.bikesched.event` and
  `org.bikesched.event.listener`: public API interfaces, records, enums, and exceptions; the
  listener SPIs and their reported types live in the `listener` subpackages.
- `org.bikesched.admin`: read-only lookups behind `Bikesched.admin()` (`Admin`, query and summary
  records) for the admin UI and custom tooling; summaries never carry payloads or results.
- `org.bikesched.internal` and its subpackages: every implementation (`Default<X>`), not exported.
  Members used across these packages are `public`; dependencies only point downwards:
  - `internal`: `DefaultBikesched` (wiring, lifecycle, API), `DefaultAdmin`, `Settings`
  - `internal.json`: `RawJsonMapper` (the default mapper, passes JSON strings through)
  - `internal.node`: `DefaultNode` (the node's name, id and closed state), `NodeHeartbeat`,
    `NodeCleanUp` (releases jobs of dead nodes, removes them)
  - `internal.job`: job builders, `JobProcessor`/`DefaultJob` (execution, steps, compensation,
    event waits via `DefaultEventWait`, sleeps, polls via `DefaultPollBuilder`; `JobSuspended` with
    its sealed `Suspension` ends an attempt at a wait, sleep or poll; `StepGuard` rejects nested
    steps; `CompensationReplay` replays the handler of a job claimed to be compensated),
    `JobLogContext` (MDC of a job's thread), `JobScheduler` (stores built jobs, registers and
    removes recurring jobs), `JobRegistry` (handlers, recurring jobs and payload types of a node),
    `JobAttempt` (timeout timer), `HardTimeout` (abandons attempts that ignore their timeout),
    `JobSlot`, `RecurringJob`, `RecurringJobRepair`, `HistoryCleanUp`, `JobCleanUp`,
    `DueJobsNotifier` (tells the own poller and, batched, the other nodes about jobs made due), `DueTimes` (the poller's wake-ups, implemented in `claim`)
  - `internal.job.claim`: `JobPoller` (claiming and running jobs: poller, job slots, job threads,
    timeouts, wake-ups; configured by `JobPollerSettings`), `WakeUpTimer` (one timer, confined to
    the poller thread), `JobNotificationListener` (`LISTEN` connection, reconnects, self-check);
    uses `internal.job`, never the other way round
  - `internal.event`: `DefaultEventBuilder`, `EventCleanUp`
  - `internal.util`: dependency-free helpers usable from the API and every implementation package
    (`ExecutorUtil`: shutting down executors; `ValidationUtil`: name and key checks matching the
    schema's length constraints, positive durations, message truncation)
  - `internal.database` (+ `.schema`): `Database`, `DatabaseAccess`, `Sql`, the stores and the rows
    they read and write (`NewJob`, `DefaultJobInfo`; a claimed job's `JobPhase` and `JobOrigin`,
    `ClaimEnd` with its `FollowUp` for ending it, the `StepKind` and `StepState` enums), `DueJobs`
    (receives the jobs committed writes made due), `DueJobsListening` (the listening connection)
- The API packages reach the implementation only through the factories in `internal.Internals`
  (plus the `Settings` record the builder fills, and `internal.util`); public API signatures never mention internal
  types. `ArchitectureTest` (ArchUnit) enforces both rules and the package dependencies above.

New dependencies for core need a very good reason: integrations users may want to swap become a
small SPI with a dependency-free default; concrete libraries are test dependencies only.

## API conventions

- Callback interfaces are `<X>Handler` with one `handle(...)` method (`JobHandler`, `StepHandler`,
  `StepFailureHandler`, `CompensationHandler`); they receive the context object (e.g. the `Step`,
  the `FailingStep`), not just a value. Exception: `JobTypeHandler` adds `type()` for class-based
  handlers registered without a separate type argument.
- Observers are `<X>Listener` SPIs with `on<Event>(record)` default no-op methods (`job.listener.JobListener`, `event.listener.EventListener`),
  called synchronously; their exceptions are logged and ignored. Each event passes one record, so
  fields can be added without breaking implementations.
- Everything handed to user code (job, builders, `JobFailure`, `EventWait`) is a public
  **interface**; implementations are `Default<X>` in `internal`.
- Builders: one fluent path ending in a verb that performs the action (`newJob(type)…schedule()`,
  `…schedule(Recurrence)` for a recurring job,
  `newEvent(name, key)…publish()`, `newStep(name, Class)…run(handler)`). Mutually exclusive options
  are separate builder types (`NodeEventBuilder` with `connection(…)`, `StepEventBuilder` with
  `stepName(…)`; `connection(…)` → `TransactionalJobBuilder`, which has no
  `schedule(Recurrence)`), enforced at compile time, not with runtime checks. Overloads of the
  verb are distinguished by argument type (`Instant`/`Duration` vs. `Recurrence`).
- Boolean accessors use the `is` prefix (`isPolling`, `isEnded`, `isFixedRate`); builder setters are
  named after the property (`nodeName(String)`).
- SPI defaults are static factories on the interface (`RetryBackoff.none()`); a default that only
  makes sense as "nothing configured" stays internal instead (`RawJsonMapper`, used by `Internals`
  when no `jsonMapper` was set).
- Typed variants sit next to the string-based API, never replacing it: `JobType<T>`/`EventType<T>`
  (records: name + payload class) select overloads (`newJob(JobType)`, `registerJobHandler(JobType,
  TypedJobHandler)`, `newEvent(EventType, key)`, `waitForEvent(step, EventType, key)`). Builders
  carry the payload type as `P` (`JobBuilder<P>`, `EventBuilder<P>`; `Object` for strings). Keep
  the Kotlin API on reified generics instead.
- Results get their own type in the feature package (`job.JobHistoryEntry`); lookups return
  `Optional` of a minimal interface (`findJob` → `JobInfo` with a `JobState` enum).
- No exceptions for control flow: intent is a flag checked after the handler (`job.cancel()`,
  `job.fail()`), except suspending at an event wait (internal `Error`, see `JobSuspended`).
- New mechanisms are opt-in: jobs not using them behave exactly as before (no extra state, replay
  or round-trip).
- Nullness: the exported core packages are `@NullMarked` (JSpecify, ADR 0041). Mark values that
  may be `null` with `@Nullable` (type-use position: `@Nullable String`, `java.time.@Nullable
  Duration`); type parameters of user values are `<T extends @Nullable Object>`, with
  `@Nullable T` where `null` occurs for any `T` (no payload, a `null` step result). Lookups keep
  returning `Optional`.
- Breaking API changes are fine while unreleased; rename across code, tests, examples, README and
  `docs/` in one go.

### Kotlin module (`org.bikesched.kotlin`)

- Kotlin-style entry points are extensions on `Bikesched` with named arguments and an optional
  trailing handler, implemented with the Java builders underneath: `handleJobs<T>(type) { payload -> }`
  (the `Job` is the receiver), `createJob(…)`, `createRecurringJob(…)`, `publishEvent(…)`, and in
  handlers `step<T>(name, compensation = …) { }` and
  `waitForEvent<T>(step, name, key, timeout, onFailure = …)`.
- Reified helpers do the `as T` cast inline, so a non-null `T` with a `null` value throws. `Unit`
  steps run as `Void` steps (no `Unit` reaches the `JsonMapper`).
- Use distinct names rather than overloading a Java member: a member always wins over an extension
  with the same signature (hence `findJobOrNull`, `node.idOrNull`, `handleJobs`).
- Keep one Kotlin style per operation; don't add parallel builder-ish extensions.

## Database

- All tables are prefixed `bikesched_`. Migrations are plain SQL in
  `bikesched-core/src/main/resources/org/bikesched/migrations`, applied by the own `SchemaMigrator`
  (no Flyway/Liquibase). While unreleased, schema changes go **into `V001` in place**; local
  databases then need `docker compose down -v` (checksum mismatch).
- Name and key columns are `text` with a `char_length` CHECK matching `ValidationUtil` (200 for
  names, 500 for event keys); new ones get both. Message columns (`last_error`, `cancel_message`)
  have no CHECK: the stores pass them through `ValidationUtil.truncateMessage`.
- New states go into the table's CHECK constraint. History rows are never deleted on state
  changes; they get a terminal state (`CANCELLED`, `COMPENSATED`) other code paths ignore.
- A new table also goes into `SchemaMigratorTest` (drop list and expected tables) and the table list
  in `docs/database.md`.
- Changes of jobs and steps are recorded in the job history (optional): a store makes the change
  through `history.change(…)` (a transaction only if the history is enabled) and passes the
  changed rows to `history.record(db, …)` in the same transaction. New state changes do the same.
- JDBC only inside the stores (`JobStore`, `StepStore`, `EventStore`, `NodeStore`,
  `HistoryStore`, and the read-only `AdminStore` across the tables), which receive a
  `DatabaseAccess` (no reference to `Database`, which only wires them). Statements go through the
  `Sql` handle: `one`/`list`/`update`/`execute`, `Sql.Row` getters,
  `Sql.millis/seconds/timestamp/typed/array` for durations, typed nulls and arrays. No
  `PreparedStatement`, `ResultSet` or `catch (SQLException)` in stores.
- `access.withConnection(db -> …, e -> new BikeschedException("… failed", e))`, `inTransaction`,
  `inReadOnlyTransaction`, `onCallerConnection(connection, …)`: the last argument is a lazy
  exception handler, called only for an `SQLException`. A caller's connection is never committed,
  rolled back, closed or switched to auto-commit.
- Statements that make jobs due report it on their `Sql` handle: `db.dueNow(type)` for jobs due
  now (other nodes are notified after the commit, or in the caller's transaction), `db.dueAt(type,
  time)` for the writing node's own wake-up (suspensions, retries), `db.due(type, time)` for new
  jobs. A new statement inserting or updating `bikesched_jobs` must be classified in
  `DueJobsWritePathsTest`; a forgotten report makes that path up to one poll interval slower.
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
- Core tests use the default mapper (store tests: `AbstractStoreTest.JSON_TEXT`): payloads and step
  results must be valid JSON text
  (`"\"x\""`, not `"x"`).
- Kotlin tests share `TestDatabase` and map data classes with `kotlinJsonMapper` (bikesched-jackson
  plus Jackson's Kotlin module); use
  `unique(name)` for job types and event names, computed outside handlers (handlers replay).
- Verify with `./gradlew build` and count results from `build/test-results/test/*.xml`; rerun
  timing-sensitive tests a few times with `--tests … --rerun`.

## Documentation

- Markdown (docs, specs, ADRs, `IDEAS.md`) is wrapped at 100 columns like the code (tables may
  stay longer) and uses no em-dashes: rewrite with a comma, a colon or two sentences.
- `IDEAS.md` (repository root) collects possible future improvements; remove an entry once it is
  implemented or decided against (and its section heading if it becomes empty). An entry states
  the problem, a sketched solution as bullets and an "Outcome:" line; a choice that is still open
  lists the options under "To decide between:" without pre-deciding one. Once an idea gets a spec,
  the entry shrinks to one paragraph linking it.
- `docs/specs/` holds design specs written before an implementation lands, one file per feature
  (`bikesched-spring-boot-starter.md`, named after the module). A spec has a status line (`draft`,
  later `implemented` with its ADR and user-docs links) and states the goal with a short usage
  sample, what exists today if it changes existing behaviour, the scope (in scope / not in scope),
  the module and dependencies, the design with rejected alternatives, a test plan and
  implementation steps, its open questions (each with a proposal) and the decisions taken; settle
  the open questions first, then implement. Specs are concise: they state what is built, not a
  survey of what is possible. Deviations found while implementing go into a "Decided while
  implementing" section. `docs/specs/index.md` lists
  every spec with its creation date and status, oldest first: add a row for a new spec and update
  the status when it changes. Keep the spec updated while
  implementing instead of writing it up afterwards, and link it from the README section of the
  module once it exists. The user-facing page in `docs/` and the ADR are written with the
  implementation, not with the spec. Specs are internal: they are not linked from `docs/index.md`.
- `docs/decisions/` holds the Architecture Decision Records (`NNNN-short-title.md`, Nygard format:
  Status, Context, Decision, Consequences; copy `template.md`). A significant design decision
  (schema, API shape, a rejected alternative worth remembering) gets a new ADR in the same change,
  listed in `docs/decisions/README.md`. Accepted ADRs are not rewritten: a changed decision gets a
  new ADR, and the old one's status becomes "Superseded by ADR NNNN".
- `README.md` stays minimal: pitch, feature list, quick start, links into `docs/`, examples and
  build instructions. Details go into `docs/`, one page per concept, linked from `docs/index.md`:
  `jobs.md` (scheduling, handlers, `Job` method table, failures, cancellation, timeouts), `steps.md`
  (steps, compensation), `recurring-jobs.md`, `events.md`, `configuration.md` (builder option
  table, nodes, JSON mapping, logging), `kotlin.md` (extension tables), `database.md` (tables,
  indexes, migrations), `patterns.md` (one section per problem: a short sketch, why it works, and
  a link to the runnable example solving it; new examples solving a common problem get a pattern),
  `changing-handlers.md` (what a job keeps across deploys, safe and unsafe handler changes; update
  it when replay or what a step stores changes).
- Keep `docs/` in sync in the same change: samples, the builder option table (defaults checked
  against the code), the Kotlin tables and the database tables. Samples are not compiled, so check
  them against the API; check relative links and anchors after moving sections.
- Public Javadoc must not link to non-exported classes; exceptions need `@Serial` ids and comments
  on serialized fields.

## Commits

Never commit or push unless the user explicitly asks for it in the current request. The user
reviews the uncommitted changes first; a request to commit covers only the work done up to that
moment, so later changes (also follow-up fixes) stay uncommitted until the next explicit request.
Push only when the request says "push".

Use [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/):
`<type>[optional scope][!]: <description>`, with a blank line before an optional body and footers.
Use an appropriate type (`feat`, `fix`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`, etc.);
mark breaking changes with `!` or a `BREAKING CHANGE:` footer. Keep the description concise and add a
few `-` bullets in the body for user-visible changes. When asked to commit, `git fetch` first and
rebase local commits onto `origin/main` if it moved (e.g. merged Renovate PRs); when asked to push,
push only as a fast-forward. A green local `./gradlew build` is enough before pushing; only after
changes to `.github/workflows/` wait for the GitHub Actions run (`gh run watch`) and check that it
succeeds.
