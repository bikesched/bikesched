---
title: "Getting started"
weight: 1
---

# Getting started

This page adds bikesched to an application and runs a first job. [Configuration](configuration.md)
covers the builder options, nodes, JSON mapping and logging after that.

## Requirements

- Java 21 or later. Every job runs on its own virtual thread.
- PostgreSQL. bikesched's own tests run against PostgreSQL 18. bikesched creates and migrates its
  tables (`bikesched_*`) on first use, so the database user needs the right to create tables in
  the data source's default schema, unless migrations run as a separate deploy step (see
  [Database](../operations/database.md#disable-automatic-migration)).
- A `javax.sql.DataSource` for that database, ideally a connection pool such as
  [HikariCP](https://github.com/brettwooldridge/HikariCP).

## Installation

All modules share the group `org.bikesched` and one version. `bikesched-core` is all you need; it
brings the PostgreSQL JDBC driver and `slf4j-api` along.

> [!NOTE]
> These docs are for version `0.1.0-SNAPSHOT`; the
> [changelog](https://github.com/bikesched/bikesched/blob/main/CHANGELOG.md) lists the releases.
> Releases and snapshots are published to GitHub Packages, which needs a GitHub token with the
> `read:packages` scope even for public packages, hence the repository blocks below.

Gradle (Kotlin DSL), with the token in the Gradle properties `gpr.user` and `gpr.token` (e.g. in
`~/.gradle/gradle.properties`):

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/bikesched/bikesched")
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

Maven, with the token in a `<server>` entry with the id `github` in `~/.m2/settings.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/bikesched/bikesched</url>
        <snapshots><enabled>true</enabled></snapshots>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>org.bikesched</groupId>
        <artifactId>bikesched-core</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

```xml
<!-- ~/.m2/settings.xml -->
<servers>
    <server>
        <id>github</id>
        <username>your-github-user</username>
        <password>your-token-with-read-packages</password>
    </server>
</servers>
```

Optional modules are added the same way, with the same version:

| Artifact | Adds |
|---|---|
| `bikesched-jackson` | typed payloads and results mapped with Jackson, see [JSON mapping](configuration.md#jackson) |
| `bikesched-kotlin` | [Kotlin extensions](../integrations/kotlin.md) |
| `bikesched-annotations` | job handlers as [`@JobHandler` methods](../integrations/annotations.md) |
| `bikesched-cron` | [cron expressions](../jobs/recurring-jobs.md) for recurring jobs |
| `bikesched-micrometer` | [Micrometer meters](../operations/metrics.md) |
| `bikesched-spring-boot-starter` | the [Spring Boot](../integrations/spring.md) auto-configuration, which replaces the setup below |
| `bikesched-admin` | the [admin UI](../operations/admin.md) (or `bikesched-admin-shaded`, with nanojson relocated) |
| `bikesched-test` | [test nodes and wait helpers](../integrations/testing.md), as a test dependency |

## A first job

Build one `Bikesched` instance per application, register a handler for each job type and schedule
jobs. Building the instance migrates the schema, registers the node and starts polling for due
jobs; closing it stops the node.

```java
DataSource dataSource = ...; // your PostgreSQL DataSource

try (Bikesched bikesched = Bikesched.builder().dataSource(dataSource).build()) {

    // Register a handler for a job type ...
    bikesched.registerJobHandler("send-mail", job -> mailer.send(job.payload()));

    // ... and schedule a job of that type, due right away
    UUID jobId = bikesched.newJob("send-mail")
            .payload("{\"to\": \"alice@example.com\"}")
            .schedule();
}
```

Without a JSON mapper, payloads are JSON text. With `bikesched-jackson`, handlers and builders take
records instead:

```java
record Mail(String to) {}

JobType<Mail> sendMail = JobType.of("send-mail", Mail.class);

Bikesched bikesched = Bikesched.builder()
        .dataSource(dataSource)
        .jsonMapper(new JacksonJsonMapper())
        .build();

bikesched.registerJobHandler(sendMail, (job, mail) -> mailer.send(mail.to()));
bikesched.newJob(sendMail).payload(new Mail("alice@example.com")).schedule();
```

In an application, the instance lives as long as the application: build it at startup and close it
on shutdown. Every application instance connected to the same database is a node, and the nodes
share the due jobs between them.

To see it running without writing code, start PostgreSQL with `docker compose up -d` in a clone of
the repository and run the [simple example](../../bikesched-examples/README.md#simple) with
`./gradlew :bikesched-examples:java:runSimple`.

## Next steps

- [Jobs](../jobs/README.md): retries, failure handlers, cancellation, timeouts and the job history
- [Multi-step jobs](../multi-step-jobs/README.md): durable steps, event waits and child jobs
- [Patterns](../patterns.md): common problems and the examples solving them
