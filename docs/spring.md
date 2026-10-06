# Spring Boot

The module `bikesched-spring-boot-starter` (package `org.bikesched.spring`, Spring Boot 4.1) wires a
`Bikesched` node from the application context: with a `DataSource` bean present, the context gets a
node, the `@JobHandler` methods of its beans are registered and polling starts with the application.
No `@EnableBikesched`, no builder code. Scheduling stays the application's job: inject `Bikesched`
and call `newJob(…)` from anywhere. Core stays free of Spring; only this module depends on it.

## Quick start

```kotlin
dependencies {
    implementation("org.bikesched:bikesched-spring-boot-starter:0.1.0-SNAPSHOT")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")   // a pooled DataSource
}
```

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/bikesched
spring.datasource.username=bikesched
spring.datasource.password=bikesched
```

```java
@SpringBootApplication
public class Application { public static void main(String[] args) { SpringApplication.run(Application.class, args); } }

@Component
class InvoiceJobs {
  @JobHandler(type = "send-invoice")
  public void sendInvoice(Job job, Invoice invoice) { mailer.send(invoice); }
}
```

Anywhere else: `@Autowired Bikesched bikesched; bikesched.newJob("send-invoice").payload(…).schedule();`.

The [Spring example](../bikesched-examples/README.md#spring-boot) in `bikesched-examples` runs this
against the compose database (`./gradlew :bikesched-examples:runSpring`) and shows transactional
scheduling and event publishing with two services that share no data.

## Handlers

Discovery finds every bean with `@JobHandler` methods — including AOP-proxied ones (`@Transactional`,
`@Cacheable`, …) — and invokes them **through the proxy**, so the advice applies. Beans implementing
`JobTypeHandler` are registered the same way. Registration happens before polling starts, so a node
never claims a job it cannot run; a bean created later (`@Lazy`, prototype) registers when it is
created. Duplicates fail startup with a message naming the bean and the method — there is no way to
downgrade this to a warning, and none intended. See [Annotations](annotations.md) for the method
signature rules.

**Transactional handlers**: a handler runs on a bikesched job thread, never inside an ambient
transaction, so `@Transactional` with the default propagation `REQUIRED` simply starts a new one —
`REQUIRES_NEW` written explicitly documents that intent. The starter logs a warning once per handler
method that uses the default propagation; it only sees annotations, so a handler that uses a
`TransactionTemplate` needs no annotation and gets no warning. Step results and job state are always
written by bikesched outside the handler's transaction, like anywhere else
([steps.md](steps.md)).

**Scheduling and publishing inside your transaction**: pass the transaction's connection to the
builder, and the job row (or event) commits and rolls back with it:

```java
@Transactional
public UUID placeOrder(String customer, String item) {
  Order order = orders.save(new Order(customer, item));
  return bikesched.newJob(FULFILL_ORDER)
      .payload(new FulfillOrder(order.getId()))
      .connection(BikeschedTransactions.transactionalConnection(dataSource))
      .schedule();
}
```

`BikeschedTransactions.transactionalConnection(dataSource)` returns the connection bound to the
caller's transaction for that `DataSource`, or `null` without one (then the builder schedules
autonomously). Recurring jobs cannot be scheduled this way — their registration lives in the node's
memory. The same applies to `newEvent(name, key).connection(…).publish()`.

## Lifecycle

The node is built with polling disabled, and a `SmartLifecycle` (last phase) starts it once the rest
of the context is up: it migrates the schema (`migrate-on-startup`, below) and registers the node,
sends heartbeats and polls. On shutdown, Spring closes the node before destroying the `DataSource`
it uses: unfinished jobs are released and running handlers awaited (bounded by their timeouts), so
another node takes over.

- `bikesched.polling.enabled=false` builds a node that schedules only: it registers with no
  database, executes nothing and sends no heartbeats. Call `startPolling()` yourself later if such
  an instance should also work.
- `bikesched.migrate-on-startup=true` (the default) migrates and validates the schema at startup,
  **even when this instance never polls** — a missing table, a checksum mismatch or an unreachable
  database then fails the context instead of the first job at 3 a.m. Set it to `false` for
  deployments that must start before their database is reachable; the schema is then migrated
  lazily on first use.
- Node names must be unique among live nodes; a second context with the same name fails to start
  (the intended guard). Several nodes in one JVM therefore need distinct `bikesched.node-name`s.

## Properties

Every builder option ([configuration.md](configuration.md)) has a `bikesched.*` property; unset ones
keep the builder default. Relaxed binding applies (`poll-interval: 1500ms` and friends).

| Property | Default | Note |
|---|---|---|
| `bikesched.node-name` | `<spring.application.name>-<host name>` | unique per live node; the host name keeps replicas apart |
| `bikesched.data-source` | the single / `@Primary` `DataSource` | the **bean name** of the one to use, never a JDBC URL; with several candidates and neither the property nor a `@Primary`, the context fails listing them |
| `bikesched.poll-interval` | 5 seconds | a job scheduled "now" starts within one interval |
| `bikesched.heartbeat-interval` | 5 seconds | |
| `bikesched.node-timeout` | 30 seconds | must be longer than the heartbeat interval (validated) |
| `bikesched.max-concurrent-jobs` | 10 | |
| `bikesched.polling.enabled` | `true` | see Lifecycle |
| `bikesched.default-job-timeout` | none | |
| `bikesched.default-event-timeout` | none | |
| `bikesched.retry-backoff.fixed` or `bikesched.retry-backoff.exponential.{initial,max}` | none | mutually exclusive; other combinations fail the context |
| `bikesched.event-retention` | 7 days | |
| `bikesched.event-clean-up-interval` | 1 hour | |
| `bikesched.node-clean-up-interval` | 15 seconds | |
| `bikesched.job-history-retention` | off | enables the [job history](jobs.md#job-history) |
| `bikesched.job-history-clean-up-interval` | 1 hour | |
| `bikesched.job-retention` | 7 days | |
| `bikesched.job-clean-up-interval` | 1 hour | |
| `bikesched.statement-timeout` | data source | |
| `bikesched.hard-timeout-grace` | off | |
| `bikesched.migrate-on-startup` | `true` | see Lifecycle |
| `bikesched.metrics.enabled` | `true` | only relevant with Micrometer (below) |

A `BikeschedCustomizer` bean (a `void customize(BikeschedBuilder)` functional interface, applied in
`@Order` after the properties) covers everything else — another `JsonMapper`, a `JobListener`, a
computed timeout — and a user-defined `Bikesched` bean makes the auto-configuration back off
entirely (define one in tests if you want to set builder options directly).

## JSON and metrics

With `bikesched-jackson` on the classpath, the starter registers a `JsonMapper` built from the
context's Jackson 3 `ObjectMapper` (Boot's `JacksonAutoConfiguration`, or yours) — so job payloads
are ordinary records, mapped exactly like the rest of the application serializes JSON. Without the
module, payloads stay JSON strings ([configuration.md](configuration.md#json-mapping)). A user
`JsonMapper` bean wins.

With `bikesched-micrometer` and a `MeterRegistry` bean (Boot's Actuator/micrometer registry), an
automatic `MicrometerJobListener` records the meters of [metrics.md](metrics.md), tagged `node` with
the resolved node name — so nodes sharing a registry do not overwrite each other's gauges. Turn it
off with `bikesched.metrics.enabled=false` or register your own `MicrometerJobListener` bean.

All `JobListener` beans in the context are composed and passed to the node; each gets the same
events.

## Out of scope (v1)

Cron/recurring declarations on `@JobHandler` (register recurring jobs programmatically as before),
Actuator health indicators, and any interop with `@Scheduled` or Spring Batch.
