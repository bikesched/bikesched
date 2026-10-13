# Specification: `bikesched-test`, testing support for applications using bikesched

Status: **draft**, open questions settled (§9)

## 1. Goal

An application that schedules jobs with bikesched gets rid of the boilerplate every example test
repeats today: building a uniquely-named test node and polling for a job to reach an expected state.

```java
DataSource dataSource = ...; // the application's own test database

try (Bikesched bikesched = BikeschedTest.testNode(dataSource).jsonMapper(new JacksonJsonMapper()).build()) {
  bikesched.registerJobHandler(new TicketOrderHandler(...));
  UUID jobId = bikesched.newJob(CinemaJobs.TICKET_ORDER).payload(order).schedule();

  JobInfo job = BikeschedTest.awaitJob(bikesched, jobId).untilSucceeded();
  assertThat(job.result(Receipt.class)).isPresent();
}
```

`testNode` returns a `BikeschedBuilder` preconfigured for tests (a unique node name, a short poll
interval); `awaitJob` waits for a job to reach a state, end, or have a given set of steps succeeded,
without the caller writing its own Awaitility condition or querying `bikesched_steps` directly.

## 2. Non-goals (v1)

- **Owning a database.** The caller supplies the `DataSource`; `bikesched-test` never starts, resets
  or migrates a database, and does not depend on Testcontainers (settled: 2026-10-13, caller-provided
  database). A future spec may add an opt-in fixture (Testcontainers Postgres, a JUnit extension) on
  top of this module once a real need shows up; it does not block this one.
- **Owning the node's lifecycle.** `testNode` returns a builder; the caller still calls `build()` and
  closes it (try-with-resources, `@AfterEach`, …), exactly as the examples do today (settled:
  2026-10-13, factory only).
- **A JUnit extension or AssertJ assertions.** No `@BikeschedTest`, no `assertThat(job)...`. Adding
  either is possible later behind the same non-goal reasoning as Testcontainers: only once repeated
  boilerplate justifies the dependency.
- **Spring Boot test support.** The starter's auto-configured node is exercised through its own test
  slices (`SpringExampleTest` starts the whole application); `bikesched-test` targets applications
  that build a `Bikesched` node directly, Spring or not. No Spring-specific helper in v1.
- **Mocking or faking `Bikesched`.** Handlers run against a real node and a real PostgreSQL, as
  today; the library does not add an in-memory or no-op implementation.
- **Exposing Awaitility (or any other third-party test library) in the public API.** It is an
  implementation detail of the wait helpers (§4.2); a test that needs its full API (e.g. a custom
  `ConditionFactory`) adds `awaitility` itself, as the examples already do.
- **Kotlin extensions.** `bikesched-kotlin` is not touched by this spec; a follow-up can add
  idiomatic wrappers once this module's shape has proven itself in the Java examples.

## 3. Module and dependencies

New module **`bikesched-test`**, package and JPMS module `org.bikesched.test`, Java only (like
`bikesched-jackson`, `bikesched-annotations`):

| Dependency | Scope | Why |
|---|---|---|
| `bikesched-core` | `api` | `Bikesched`, `BikeschedBuilder`, `JobInfo`, `JobState`, `Admin`, `StepInfo` appear in this module's own public API |
| `awaitility` | `implementation` | backs the wait helpers; never appears in a public signature, so it stays a transitive-free implementation detail |
| `libs.bundles.testing` (`junit-jupiter`, `assertj-core`, `testcontainers-postgresql`, …) | `test` | this module's own tests, same as every other module |

No `postgresql` dependency: the caller's `DataSource` is already connected to a driver.

## 4. Design

### 4.1 Node factory

```java
public final class BikeschedTest {
  private BikeschedTest() {}

  /** Like {@link #testNode(DataSource, String)} with the prefix {@code "test"}. */
  public static BikeschedBuilder testNode(DataSource dataSource) { ... }

  /**
   * A builder for a test node: {@code dataSource}, a node name {@code <nodeNamePrefix>-<random
   * UUID>} (unique per call, so tests run in parallel without clashing) and a poll interval of 50
   * ms, so a job scheduled "now" is claimed promptly. Every other option (JSON mapper, heartbeat
   * interval, concurrency, …) is the caller's.
   */
  public static BikeschedBuilder testNode(DataSource dataSource, String nodeNamePrefix) { ... }

  public static JobAwait awaitJob(Bikesched bikesched, UUID jobId) { ... }
}
```

This mirrors `AbstractBikeschedTest.testNode` (the library's own test base class) and
`BikeschedUtil.builder` in the examples, generalized for a caller's `DataSource` instead of a shared
container, and with a generated unique name instead of a caller-supplied one (every current call site
already appends `UUID.randomUUID()` itself).

### 4.2 Waiting for a job

```java
public final class JobAwait {
  JobAwait timeout(Duration timeout); // default 10 seconds

  /** Waits until the job's state satisfies {@link JobState#isEnded()}. */
  JobInfo untilEnded();

  /** Waits until the job is in one of the given states. */
  JobInfo untilState(JobState... states);

  /** Like {@code untilState(JobState.SUCCEEDED)}. */
  JobInfo untilSucceeded();

  /**
   * Waits until the job's succeeded steps, in the order they started, are exactly the given names
   * (see {@link Admin#findSteps(UUID)}).
   */
  List<StepInfo> untilSucceededSteps(String... names);
}
```

- Built only through `BikeschedTest.awaitJob(bikesched, jobId)`; no public constructor.
- Internally an Awaitility `await().atMost(timeout).pollInterval(50ms).untilAsserted(...)` reading
  through `bikesched.findJob(jobId)` (§`untilEnded`/`untilState`/`untilSucceeded`) or
  `bikesched.admin().findSteps(jobId)` (`untilSucceededSteps`, replacing the raw SQL examples such as
  `CinemaTest.succeededSteps` use today). `JobAwait` itself is not an Awaitility type, so Awaitility
  stays swappable without breaking this API (§2).
- Every `until*` method throws `org.awaitility.core.ConditionTimeoutException` on a timeout (not
  wrapped): it already carries a clear message and is a `RuntimeException`, so wrapping it would only
  hide the cause and the pending `AssertionError` on the last attempt.
- `untilState`/`untilSucceeded`/`untilEnded` return the `JobInfo` read when the condition passed, so
  a caller that also wants the result does not read the job a second time.

### 4.3 Rejected alternatives

- **A generic `await(Supplier<T>, Predicate<T>)` escape hatch.** Collapses to Awaitility's own
  `until(Callable, Matcher)` with extra indirection; a caller who needs something `JobAwait` does not
  cover can depend on Awaitility directly, which it already does in every example.
- **Returning the Awaitility `ConditionFactory` pre-configured with the test defaults** (`await()` on
  `BikeschedTest`) instead of `JobAwait`. Simpler to implement, but makes Awaitility part of this
  module's public API (§2): a later switch to a different polling implementation would be breaking.
- **One static method per condition on `BikeschedTest`** (`BikeschedTest.awaitJobSucceeded(bikesched,
  jobId)`, …) instead of the `JobAwait` builder. Rejected: a growing number of conditions (ended,
  state, succeeded steps, and the admin example's "event steps" or "waiting steps" later) multiplies
  the overloads; `timeout(...)` would need a parameter on every one of them instead of once.

## 5. Documentation

- A new page `docs/testing.md`: the goal and usage sample (§1), `testNode`'s defaults, every
  `JobAwait` method, and a note that the example tests use this module (link to
  `bikesched-examples/java`).
- Linked from `docs/index.md`'s Contents list, after Database.
- `README.md`: one line in the module/feature overview.

## 6. Testing

New module tests (`AbstractBikeschedTest`-style, one shared Postgres container like the other
modules):

1. `testNode(dataSource)` / `testNode(dataSource, prefix)`: builder options, unique node names across
   two calls, poll interval.
2. `awaitJob(...).untilEnded()` / `untilState(...)` / `untilSucceeded()`: returns once the job reaches
   the state, returns the `JobInfo` read; times out with `ConditionTimeoutException` for a job that
   never reaches it.
3. `untilSucceededSteps(...)`: a job with steps that succeed one by one is waited on until the given
   names match; mismatched or missing steps keep the wait going until the timeout.
4. `timeout(...)` overrides the default.

## 7. Implementation steps

1. New module `bikesched-test` (build file, `module-info.java`, package-info, entries in
   `settings.gradle.kts` and `gradle/libs.versions.toml` if a version pin is needed), `BikeschedTest`
   and `JobAwait`, tests from §6.
2. `docs/testing.md`, `docs/index.md` link, `README.md` line.
3. Migrate `bikesched-examples/java`: every test under `src/test/java/org/bikesched/examples/**` that
   builds its own node (all 14 test classes) switches to `BikeschedTest.testNode(Database.dataSource())`;
   every test that polls for a job's end state switches to `BikeschedTest.awaitJob(...)`;
   `CinemaTest.succeededSteps` (and any other raw-SQL step check, e.g. in `FanOutExampleTest`,
   `TaxiBookingTest`) switches to `untilSucceededSteps`. Raw SQL that checks the application's own
   tables (e.g. `ChatExampleTest`'s dedup table, `RateLimitExampleTest`'s bucket state) is unrelated to
   this module and stays as is. Remove the now-unused `awaitility` direct usage from these test
   classes where `bikesched-test` fully replaces it (the module still needs `awaitility` for examples
   that wait on things other than a job, if any).
4. `bikesched-examples/java/build.gradle.kts`: add `testImplementation(project(":bikesched-test"))`.
5. Leave `bikesched-examples/kotlin` (no tests today) and `bikesched-examples/spring`
   (`SpringExampleTest` asserts on the application's own tables through a Testcontainers connection,
   not on bikesched job state) unchanged; note in `docs/testing.md` that a Spring-specific helper is
   future work (§2).
6. Remove the entry from `IDEAS.md` if one exists; add the `docs/specs/index.md` row.

## 8. Effort

Small: two small classes plus their tests, then a mechanical migration of the Java examples' test
classes (no behaviour change, same assertions through a shared helper).

## 9. Decisions (former open questions)

1. **Default timeout and poll interval for `JobAwait`.** 10 seconds / 50 ms, matching
   `AbstractBikeschedTest`'s and the examples' own Awaitility configuration today;
   `timeout(Duration)` covers a slower job.
2. **`untilSucceededSteps` is part of v1.** It is the one place today's examples (`CinemaTest`)
   resort to raw SQL instead of the public API, so leaving it out would not remove that pattern.
3. **`JobAwait` via a standalone `BikeschedTest.awaitJob(bikesched, jobId)` static factory**, not a
   method on `Bikesched`. A core method would need its own hand-written polling loop (core has no
   Awaitility dependency and runtime deps stay JDBC + slf4j only), pull a test-only concern into the
   production artifact, and break the "new mechanisms are opt-in" rule. The module keeps the core API
   untouched, consistent with `bikesched-jackson`/`bikesched-annotations` never adding a method to
   `Bikesched` either.
