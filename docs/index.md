# bikesched documentation

bikesched is a job scheduler for Java that keeps all of its state in PostgreSQL. Every application
instance connected to the database is a *node*: nodes claim due jobs with
`SELECT … FOR UPDATE SKIP LOCKED`, so many nodes share one database without stepping on each other
and without any coordinator besides PostgreSQL.

## Concepts

- **Node**: a `Bikesched` instance. It registers in `bikesched_nodes`, sends heartbeats, polls for
  due jobs and runs them on virtual threads, up to its concurrency limit. Nodes clean up after each
  other: the running jobs of a crashed node are released and retried elsewhere.
- **Job**: a unit of work of a *type*, with an optional JSON payload, a priority, a retry limit and
  a timeout. A handler registered for the type runs it.
- **Step**: a named, durable part of a job. A completed step is not executed again when the job is
  retried; its stored result is returned instead. Steps can declare a compensation that undoes them
  if the job is cancelled or fails (a saga).
- **Recurring job**: a job type with a `Recurrence`; each run is a regular job (an *occurrence*).
- **Event**: a message with a name and a key. A job can wait for an event as a step; it is
  suspended without holding a thread until the event is published, then resumes.

## Guarantees

- **At-least-once**: a job may run again, e.g. after its node crashed, so handlers (and
  compensations) should be idempotent. Durable steps keep completed work from being repeated.
- **Transactional scheduling**: jobs and events can be created in your own database transaction;
  they only become visible when you commit.
- **No lost events**: an event published while a job starts waiting for it is never missed, and a
  job that starts waiting after the event was published receives it right away (within the event
  retention).

## Contents

1. [Jobs](jobs.md): scheduling, writing handlers, retries, cancellation, job history, timeouts
2. [Steps and compensation](steps.md): durable steps and sagas
3. [Recurring jobs](recurring-jobs.md): schedules and occurrences
4. [Events](events.md): waiting for and publishing events
5. [Configuration](configuration.md): builder options, nodes, JSON mapping (`bikesched-jackson`),
   logging
6. [Kotlin](kotlin.md): the `bikesched-kotlin` extensions
7. [Annotations](annotations.md): job handlers as `@JobHandler` methods (`bikesched-annotations`)
8. [Metrics](metrics.md): `JobListener` and the Micrometer meters (`bikesched-micrometer`)
9. [Spring Boot](spring.md): the auto-configured node (`bikesched-spring-boot-starter`)
10. [Database](database.md): tables, indexes and migrations

The [examples](../bikesched-examples/README.md) show the concepts working together. The
[decision records](decisions/README.md) explain why bikesched works the way it does.
