<p align="center">
  <img src="docs/logo.svg" alt="bikesched logo: a pelican riding a bicycle with clock wheels, carrying a bag of jobs" width="200" height="200">
</p>

# bikesched

**A Java based, PostgreSQL first job scheduler with minimal dependencies**

> [!NOTE]
> bikesched is at a super early stage (`0.1.0-SNAPSHOT`) and can break at any time: APIs and the
> database schema may change without notice and without migrations.
>
> It is also a first experiment in building a library heavily with AI coding agents; hence the
> logo, a nod to Simon Willison's
> [pelican riding a bicycle](https://simonwillison.net/2024/Oct/25/pelicans-on-a-bicycle/) LLM
> benchmark.

bikesched stores jobs in PostgreSQL and lets every connected application instance (*node*) work on
them. Nodes claim due jobs with `SELECT … FOR UPDATE SKIP LOCKED`, so many nodes can share one
database without stepping on each other and without any coordinator besides PostgreSQL.

It is inspired by job schedulers like [db-scheduler](https://github.com/kagkarlsson/db-scheduler),
[JobRunr](https://www.jobrunr.io), [Quartz](https://www.quartz-scheduler.org) and
[River](https://riverqueue.com) and by durable execution engines like
[Inngest](https://www.inngest.com) and [Restate](https://restate.dev), but deliberately small:

- **Two runtime dependencies**: the PostgreSQL JDBC driver and the SLF4J API. No framework, no
  migration tool, no JSON library.
- **PostgreSQL first**: jobs, durable steps, events and nodes live in plain tables (`bikesched_*`);
  the schema is created and migrated on first use.
- **Java 26**, jobs run on virtual threads.

bikesched is not a workflow engine: there are no process models (BPMN), no visual modelling and no
orchestration across services. If you need that, look at
[Camunda](https://camunda.com), [Temporal](https://temporal.io) or similar tools.

| Module | Contents |
|---|---|
| `bikesched-core` | the scheduler; depends on the PostgreSQL driver and the SLF4J API only |
| `bikesched-jackson` | `JacksonJsonMapper`: payloads, step results and events as records, via Jackson 3 |
| `bikesched-kotlin` | Kotlin extensions: configuration DSL, typed handlers, `kotlin.time` durations |
| `bikesched-annotations` | job handlers declared as `@JobHandler` methods, see [Annotations](docs/annotations.md) |
| `bikesched-micrometer` | Micrometer meters for claims and attempts, see [Metrics](docs/metrics.md) |

## Features

- Jobs now, at a given time or after a delay, with priority, retries and timeouts; optionally in
  your own database transaction
- Durable **steps** whose results survive retries, with optional **compensation** (sagas)
- **Cancellation** from within a handler or from outside
- **Recurring jobs** with fixed-rate, daily, weekly or custom schedules
- **Events**: jobs wait for an event without holding a thread and resume when it is published
- Optional typed `JobType`/`EventType` constants, next to plain string names
- Crash recovery: jobs of dead nodes are released and retried by others; a node that lost a job
  can no longer change its steps
- Ended jobs are deleted after a retention (7 days by default); optional job history and
  statement timeout
- Pluggable JSON mapping and node, job and step ids in the SLF4J logging context

## Quick start

```java
DataSource dataSource = ...; // any PostgreSQL DataSource, e.g. a HikariCP pool

try (Bikesched bikesched = Bikesched.builder()
        .dataSource(dataSource)
        .nodeName("worker-1")              // optional, defaults to the host name
        .build()) {                    // migrates the schema, registers the node, starts polling

    // Register a handler for a job type ...
    bikesched.registerJobHandler("send-mail", job -> mailer.send(job.payload()));

    // ... and schedule jobs of that type
    bikesched.newJob("send-mail").payload("{\"to\": \"alice@example.com\"}").schedule();
}
```

Every node sharing the database polls all due jobs, regardless of their type, so all nodes should
register the same handlers. A handler that throws fails the attempt; the job is retried until its
maximum number of attempts is used up and then marked `FAILED`.

## Documentation

The [documentation](docs/index.md) covers the concepts in detail:

- [Jobs](docs/jobs.md): scheduling, writing handlers, retries, cancellation, history, retention,
  timeouts
- [Steps and compensation](docs/steps.md)
- [Recurring jobs](docs/recurring-jobs.md)
- [Events](docs/events.md)
- [Configuration](docs/configuration.md): builder options, nodes, JSON mapping, logging
- [Kotlin](docs/kotlin.md)
- [Database](docs/database.md): tables, indexes and migrations

## Examples

Runnable examples with tests, a taxi booking saga with event waits and compensation and a cinema
ticket order with a `JobTypeHandler`, live in the [`bikesched-examples`](bikesched-examples/README.md)
module.

## Building

Requirements: JDK 26 (or let Gradle provision it via the toolchain) and Docker for the tests,
which run against PostgreSQL 18 with [Testcontainers](https://testcontainers.com).

```shell
./gradlew build
```

To try it out locally, start PostgreSQL with Docker Compose and run the
[simple example](bikesched-examples/README.md#simple), which registers a handler and runs one job:

```shell
docker compose up -d                      # POSTGRES_PORT=5433 docker compose up -d if 5432 is taken
./gradlew :bikesched-examples:runSimple   # honors POSTGRES_PORT as well
```

All dependency and tool versions are managed in `gradle/libs.versions.toml`.

## Snapshots

Every successful build of `main` publishes the current snapshot (`org.bikesched:bikesched-core`,
`bikesched-jackson`, `bikesched-kotlin`, `bikesched-annotations`, `bikesched-micrometer`, version `0.1.0-SNAPSHOT`) to GitHub Packages. Reading
from GitHub Packages requires a GitHub token with the `read:packages` scope, even for public
packages:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/sbuettner/bikesched")
        credentials {
            username = providers.gradleProperty("gpr.user").get()
            password = providers.gradleProperty("gpr.token").get()   // token with read:packages
        }
    }
}

dependencies {
    implementation("org.bikesched:bikesched-core:0.1.0-SNAPSHOT")
}
```
