<p align="center">
  <img src="docs/logo.svg" alt="bikesched logo: a pelican riding a bicycle with clock wheels, carrying a bag of jobs" width="200" height="200">
</p>

# bikesched

**A PostgreSQL-backed job scheduler for Java and Kotlin: durable steps, events and retries, with
minimal dependencies and no extra infrastructure**

> [!NOTE]
> bikesched is at a super early stage (`0.1.0-SNAPSHOT`) and can break at any time: APIs and the
> database schema may change without notice and without migrations.
>
> It is also a first experiment in building a library heavily with AI coding agents; hence the
> logo, a nod to Simon Willison's
> [pelican riding a bicycle](https://simonwillison.net/2024/Oct/25/pelicans-on-a-bicycle/) LLM
> benchmark.

*[Simon](https://github.com/sbuettner), the original author*: I was looking for a
PostgreSQL-backed job scheduler with bikesched's properties and capabilities for a small app of
mine, and didn't find one that fit. So this is a bit of a pun on its own name: instead of
["bikeshedding"](https://en.wiktionary.org/wiki/bikeshed), endlessly debating what such a library
should look like, I decided to try building it myself, with AI doing much of the implementation,
around the needs I'd actually run into.

bikesched stores jobs in PostgreSQL and lets every connected application instance (*node*) work on
them. Nodes claim due jobs with `SELECT … FOR UPDATE SKIP LOCKED`, so many nodes can share one
database without stepping on each other and without any coordinator besides PostgreSQL.

bikesched is inspired by some great job schedulers, like
[db-scheduler](https://github.com/kagkarlsson/db-scheduler), [JobRunr](https://www.jobrunr.io),
[Quartz](https://www.quartz-scheduler.org) and [River](https://riverqueue.com), and by durable
execution engines like [Inngest](https://www.inngest.com) and [Restate](https://restate.dev). The
goal is to keep it small and easy to drop into your app:

- **Just two runtime dependencies** in the core: the PostgreSQL JDBC driver and the SLF4J API. No
  framework, no migration tool, no JSON library, in the spirit of
  [boring technology](https://boringtechnology.club/).
- **Extension modules when you want them**: Jackson for typed payloads, Kotlin extensions, Spring
  Boot auto-configuration, cron expressions and Micrometer metrics. Pull in only what you use.
- **PostgreSQL is all you need**: jobs, durable steps, events and nodes live in plain tables
  (`bikesched_*`), and bikesched creates and migrates the schema on first use.
- **Modern Java**: built for Java 26, with every job on its own virtual thread.

**One thing bikesched isn't**: [a workflow engine](docs/steps.md#workflows). Steps and compensation
live inside a single job, so there are no process models (BPMN), no visual modelling and no
orchestration across services. If that's what you're after, have a look at
[Camunda](https://camunda.com) (full disclosure: I used to work there, and I'm still a fan) or
similar tools.

| Module | Contents |
|---|---|
| [`bikesched-core`](bikesched-core) | the scheduler; depends on the PostgreSQL driver and the SLF4J API only |
| [`bikesched-jackson`](bikesched-jackson) | `JacksonJsonMapper`: payloads, step results and events as records, via Jackson 3 |
| [`bikesched-kotlin`](bikesched-kotlin) | Kotlin extensions: configuration DSL, typed handlers, `kotlin.time` durations |
| [`bikesched-annotations`](bikesched-annotations) | job handlers declared as `@JobHandler` methods, see [Annotations](docs/annotations.md) |
| [`bikesched-cron`](bikesched-cron) | cron expressions as `CronRecurrence` or as strings (`schedule("0 3 * * *")`, `@JobHandler`), via cron-utils, see [Cron expressions](docs/recurring-jobs.md#cron-expressions) |
| [`bikesched-micrometer`](bikesched-micrometer) | Micrometer meters for claims, attempts and published events, see [Metrics](docs/metrics.md) |
| [`bikesched-spring-boot-starter`](bikesched-spring-boot-starter) | Spring Boot auto-configuration: the node from the context, `@JobHandler` beans found automatically, see [Spring Boot](docs/spring.md) |

## Features

- Jobs now, at a given time or after a delay, with priority, retries and timeouts; optionally in
  your own database transaction, and with an idempotency key for callers that retry
- Jobs start as soon as they are due: nodes notify each other with PostgreSQL `LISTEN/NOTIFY`,
  polling is only the safety net
- Durable **steps** whose results survive retries, with optional **compensation** (sagas)
- **Failure handlers** for permanently failed jobs (e.g. a dead-letter table) and for steps (with a
  fallback result)
- **Cancellation** from within a handler or from outside
- **Recurring jobs** with fixed-rate, daily, weekly or custom schedules
- **Events**: jobs wait for an event without holding a thread and resume when it is published
- **Sleeping**: jobs pause for days with `job.sleep(…)` without holding a thread
- **Polling**: jobs check an external service until a result is ready, as one durable step
  suspended between checks
- **Child jobs**: jobs start other jobs exactly once and wait for their outcome and result without
  holding a thread
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

A handler that throws fails the attempt; the job is retried until its maximum number of attempts is
used up and then marked `FAILED`.

This is where bikesched stops looking like a regular job scheduler: a handler can run **durable
steps** and **wait for an event**, without holding a thread or re-doing work on retries or resumes:

```java
bikesched.registerJobHandler("order", job -> {
    Order order = job.payload(Order.class);

    // Creates a Stripe PaymentIntent; runs once, a retried or resumed job gets the id back instead
    String paymentIntentId = job.step("create-payment-intent", String.class,
            _ -> stripe.createPaymentIntent(order).id());

    // Suspends (no thread held) until Stripe's webhook reports on this payment intent
    PaymentUpdated webhook = job.waitForEvent("await-payment", "payment-intent-updated", paymentIntentId,
                    PaymentUpdated.class)
            .receive();

    switch (webhook.status()) {
        case SUCCEEDED -> job.step("mark-paid", () -> orders.markPaid(order, paymentIntentId));
        case FAILED -> job.step("mark-failed", () -> orders.markFailed(order, paymentIntentId));
    }
});

// in the Stripe webhook endpoint, possibly minutes or days later
bikesched.newEvent("payment-intent-updated", webhookPayload.paymentIntentId())
        .payload(webhookPayload)
        .publish();
```

A plain job scheduler would need its own tables and glue code to carry `order` and
`paymentIntentId` across that wait, to not create a second payment intent on a retry, and to
resume on another node after a crash; here it's `job.step(...)` and `job.waitForEvent(...)`. See
[Steps and compensation](docs/steps.md) and [Events](docs/events.md).

## Documentation

The [documentation](docs/index.md) covers the concepts in detail:

- [Jobs](docs/jobs.md): scheduling, writing handlers, retries, failure handlers, cancellation,
  history, retention, timeouts
- [Steps and compensation](docs/steps.md)
- [Recurring jobs](docs/recurring-jobs.md)
- [Events](docs/events.md)
- [Child jobs](docs/child-jobs.md): starting and waiting for child jobs, job results
- [Configuration](docs/configuration.md): builder options, nodes, JSON mapping, logging
- [Kotlin](docs/kotlin.md)
- [Spring Boot](docs/spring.md): the auto-configured node
- [Database](docs/database.md): tables, indexes and migrations

## Examples

Runnable examples with tests — a taxi booking saga with event waits and compensation, a cinema
ticket order with a `JobTypeHandler`, a webhook with a polling fallback, and a Spring Boot
application whose node and handlers are
wired by the starter — live in the [`bikesched-examples`](bikesched-examples/README.md) modules.

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
./gradlew :bikesched-examples:java:runSimple   # honors POSTGRES_PORT as well
```

All dependency and tool versions are managed in `gradle/libs.versions.toml`.

## Snapshots

Every successful build of `main` publishes the current snapshot (`org.bikesched:bikesched-core`,
`bikesched-jackson`, `bikesched-kotlin`, `bikesched-annotations`, `bikesched-cron`,
`bikesched-micrometer`, `bikesched-spring-boot-starter`, version `0.1.0-SNAPSHOT`) to GitHub
Packages. Reading from GitHub Packages requires a GitHub token with the `read:packages` scope, even for public
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

## License

bikesched is licensed under the [Apache License 2.0](LICENSE): you may use, modify and embed it,
also in commercial and closed-source products. *[Simon](https://github.com/sbuettner), the
original author*: it's permissive on purpose, so that anyone who runs into the same need I did can
drop it into their own project, open or closed source, without license-review friction. The name
and logo are trademarks, see the
[trademark policy](TRADEMARKS.md) for how to refer to bikesched and how to name forks.
