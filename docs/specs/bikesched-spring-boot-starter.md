# Specification: `bikesched-spring-boot-starter`

Status: **implemented** (module `bikesched-spring-boot-starter`, package `org.bikesched.spring`).
Boot 4.1.1 / Spring Framework 7.0.9. The original design notes are kept where they still explain the
code; §13 lists the decisions taken while implementing that differ from the draft, §14 the
alternatives that stay on record.

## 1. Goal

A Spring Boot application that has a `DataSource` gets a working `Bikesched` node from its context:

```java
@SpringBootApplication
public class Application { public static void main(String[] a) { SpringApplication.run(Application.class, a); } }

@Component
class InvoiceJobs {
  @JobHandler(type = "send-invoice")
  public void sendInvoice(Job job, Invoice invoice) { invoiceService.send(invoice); }
}
```

No `@EnableBikesched`, no builder code, no XML. Scheduling stays the application's job:
`bikesched.newJob(...).schedule()` from anywhere in the context.

## 2. Non-goals (v1)

- Recurring jobs declared on the annotation (`cron = …`, `every = …`). The annotation has no
  `Recurrence` support yet; recurring jobs keep being registered programmatically. Future work, with
  `bikesched-cron`.
- Actuator endpoints/health: an `HealthIndicator` for the node heartbeat. Later; Micrometer *metrics*
  are in v1 (§7.4).
- Spring Batch, `@Async`, `@Scheduled` interop and any migration of existing `@Scheduled` methods.
- A separate `-autoconfigure` artifact: the starter stays one artifact for now (§3).
- Configuring bikesched's *job* retry/backoff per handler method, and AOP-based job interception
  (`@BikeschedTransactionalJob` & co.).
- Kotlin DSL parity; the Kotlin module is used directly (its `bikesched { }` DSL stays the entry
  point for Kotlin applications that prefer it). A Kotlin-friendly `@ConfigurationProperties` class
  is not planned.
- Supporting both Boot 3.x and 4.x. Boot 4.1 only (Boot 3 uses Jackson 2; `bikesched-jackson` is
  Jackson 3).

## 3. Module, dependencies, build

One module, artifact **`bikesched-spring-boot-starter`**, package and JPMS module
`org.bikesched.spring` (decided: one artifact, named for the consumer's side of it — the project does
not split a separate `-autoconfigure` artifact for now; if that ever changes, the auto-configuration
classes stay in `org.bikesched.spring`, so the split is transparent to users):

| Dependency | Scope | Why |
|---|---|---|
| `bikesched-core` | `api` | the API it configures |
| `bikesched-annotations` | `api` | `@JobHandler`, `JobHandlers` |
| `spring-boot-autoconfigure` | `api` | `@AutoConfiguration`, conditions, `@ConfigurationProperties` (brings `spring-boot`, `spring-context/-beans/-core/-aop` transitively) |
| `slf4j-api` | `compileOnly` | core has it as `implementation`, so it is on the runtime but not the compile module path of this module |
| `bikesched-jackson` | `compileOnly` + test | only for the optional Jackson `JsonMapper` default (§7.3); must stay optional |
| `bikesched-micrometer` | `compileOnly` + test | only for the optional `MicrometerJobListener` bean (§7.4) |
| `spring-tx`, `spring-jdbc` | `compileOnly` + test via `spring-boot-starter-jdbc` | `BikeschedTransactions` and the propagation warning (§8) |
| `spring-boot-starter-test`, `postgresql`, Testcontainers | test | §11 |

Boot 4 modularized its own auto-configurations: `DataSourceAutoConfiguration` lives in
`spring-boot-jdbc` (`org.springframework.boot.jdbc.autoconfigure`) and `JacksonAutoConfiguration` in
`spring-boot-jackson` (`org.springframework.boot.jackson.autoconfigure`). We depend on neither;
`bikesched-spring-boot-starter` orders itself against the DataSource auto-configuration *by name* (§4) and takes
the `ObjectMapper`, if there is one, through an `ObjectProvider` (§7.3).

Version catalog: `spring-boot = "4.1.1"`, and the individual Spring artifacts the module needs
(`spring-boot-autoconfigure`, `spring-tx`, `spring-jdbc`, the starters) — `spring-tx`/`spring-jdbc`
are pinned to `spring-framework = "7.0.9"`, the Framework version `spring-boot-dependencies` 4.1.1
manages. No version literal appears in a `.kts`. Verified against Boot 4.1.1: the auto-configuration
imports file is `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
`@AutoConfiguration` has `beforeName`/`afterName` next to `before`/`after`, and
`AopProxyUtils.ultimateTargetClass` / `AopUtils.selectInvocableMethod` are the `spring-aop` helpers
the discovery uses.

`module-info.java`: `open module org.bikesched.spring` (Spring needs deep reflection for binding and
bean post-processing; an open module avoids a list of `opens … to …` that breaks whenever Spring
touches another package). It `requires transitive` `org.bikesched`, `org.bikesched.annotation`,
`spring.boot` and `spring.boot.autoconfigure`, plain `requires` `spring.context`, `spring.beans`,
`spring.core`, `spring.aop` and `org.slf4j`, and `requires static` the optional ones (`spring.tx`,
`spring.jdbc`, `micrometer.core`, `org.bikesched.jackson`, `org.bikesched.micrometer`) — all Spring
and Micrometer artifacts are automatic modules, so the `requires-automatic` and
`requires-transitive-automatic` lints are suppressed as `bikesched-micrometer` does.
`exports org.bikesched.spring` so the auto-configuration class can be loaded by name.

Registration: the `AutoConfiguration.imports` file contains
`org.bikesched.spring.BikeschedAutoConfiguration`. `spring.factories` carries **only** the
`FailureAnalyzers` entry (§9) — Boot 4 reads it for failure analysis, not for auto-configuration.

## 4. What the auto-configuration provides

```java
// Boot 4 ships DataSourceAutoConfiguration in spring-boot-jdbc; ordering by name avoids an
// optional dependency on it, and Jackson needs no ordering (see §7.3).
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass(Bikesched.class)
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(BikeschedProperties.class)
public class BikeschedAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  Bikesched bikesched(DataSourceSelector dataSourceSelector, BikeschedProperties properties,
      Environment environment, JobHandlerBeans jobHandlerBeans,
      ObjectProvider<BikeschedCustomizer> customizers, ObjectProvider<JobListener> listeners,
      ObjectProvider<JobTypeHandler<?>> jobTypeHandlers, ObjectProvider<JsonMapper> jsonMapper) { … }
}
```

| Bean | Conditions | Notes |
|---|---|---|
| `Bikesched` | `@ConditionalOnClass(Bikesched)`, `@ConditionalOnBean(DataSource)`, `@ConditionalOnMissingBean` | built with `autoStartPolling(false)`; Spring detects its `close()` as the destroy method, so it is destroyed before the `DataSource` it depends on |
| `DataSourceSelector` | — | resolves *which* `DataSource` (§4.1); deliberately not a `DataSource` bean, so it never becomes a candidate for other auto-configurations |
| `BikeschedProperties` | `@EnableConfigurationProperties` | §7.1 |
| `BikeschedLifecycle` (`SmartLifecycle`) | `@ConditionalOnBean(Bikesched)` | migrates and starts polling with the application (§6); deliberately *not* conditional on `bikesched.polling.enabled`, so migration runs for scheduling-only instances too |
| `JobHandlerBeanPostProcessor` | `static @Bean` (so Spring registers it early enough) | collects candidate beans (§5) |
| `JobHandlerBeans` (holder) | `static @Bean` of the same configuration, so both the BPP and the `Bikesched` factory see one instance | §5.2 |
| `JsonMapper` | nested `JacksonConfiguration` (`@ConditionalOnClass(JacksonJsonMapper)`) + `@ConditionalOnMissingBean(JsonMapper)` | from the context's `ObjectMapper` if there is one, else `JacksonJsonMapper`'s own (§7.3) |
| `MicrometerJobListener` | nested `MicrometerConfiguration` (`@ConditionalOnClass`), `@ConditionalOnBean(MeterRegistry)`, `@ConditionalOnMissingBean(MicrometerJobListener)`, `bikesched.metrics.enabled` | from the context's `MeterRegistry`, tagged with the *resolved* node name (§7.4) |
| `TransactionPropagationWarning` (`HandlerWarning`) | nested `TransactionWarningConfiguration` (`@ConditionalOnClass(Transactional)`) | §8 |

The `bikesched` factory applies properties, then customizers, then the `JsonMapper` (if any) and the
composed listener, builds the node, registers the collected handler beans (`registerAll`) and the
`JobTypeHandler` beans, and logs node name, handler count, listener count and mapper (§9).
`CompositeJobListener` is not a bean of its own: the factory composes the `JobListener` beans when
there is more than one and passes the result to the builder (§7.4).

`@ConditionalOnMissingBean(Bikesched.class)` makes a user-defined node (usually for tests) win. A
user-defined `DataSource` is required; with several candidates and none selected, the context fails
with a clear message (§4.1, §9).

### 4.1 Which `DataSource`

Applications with more than one `DataSource` (a read replica, a second business database, two schemas
in one JVM) select the one bikesched uses by bean name:

```yaml
bikesched:
  data-source: ordersDataSource   # optional; the *bean name*, not a JDBC URL
```

`DataSourceSelector` resolves the bean once, at startup:

| `bikesched.data-source` | Resolution |
|---|---|
| unset | the unique `DataSource` bean (`getIfUnique()`), or the `@Primary` one among several |
| set | the bean with that name, which must exist and be a `DataSource` |

Failure modes, all at startup and all naming the candidates (`DataSourceSelectionException`): the
property names a bean that does not exist or is not a `DataSource`; several `DataSource` beans exist
and none is `@Primary`; the property is set and the named bean is not the `@Primary` one (that is
allowed, the property wins — but it is logged at info level, since silently picking a non-primary
`DataSource` is worth knowing about). The value is a Spring *bean name* (`ordersDataSource`), never a
JDBC URL or a schema. Everything bikesched stores — jobs, steps, events, nodes, history — lives in
that database; the schema is created there (§6).

This is *not* multi-tenancy: one node uses one `DataSource`. Two applications on the same database
with different node names is the supported way to scale, and one application wanting two independent
schedulers needs two contexts (or two node beans), which the auto-configuration's
`@ConditionalOnMissingBean` supports by backing off.

## 5. Handler discovery

**Option A of the original design comparison** (collecting `BeanPostProcessor` + holder, versus
`SmartInitializingSingleton` iteration, versus `ContextRefreshedEvent`) was implemented as drafted;
§14 keeps the rejected options on record. Requirements unchanged: see every bean including AOP
proxies and invoke through the proxy, never touch the `Bikesched` bean from the post-processor,
register everything before polling starts, and pick up late (`@Lazy`, prototype) beans.

### 5.1 The collecting post-processor

`JobHandlerBeanPostProcessor` (`postProcessAfterInitialization`, `Ordered.LOWEST_PRECEDENCE` — after
Spring's auto-proxy creator, so it sees the finished proxy) resolves
`AopProxyUtils.ultimateTargetClass(bean)` and a per-class cached
`JobHandlers.hasAnnotatedMethods(Class)` filter decides whether the bean is a candidate; the BPP
never wraps or modifies a bean and only records candidates in the `JobHandlerBeans` holder.

`JobHandlerBeans.add(beanName, bean, targetClass)` registers the bean immediately if the node
already exists, otherwise it waits for `registerAll(node)`, called by the `bikesched` factory method
right after `build()` — so the order is: BPP collects every bean created during refresh, the factory
registers them all before any polling, later beans self-register on creation. `nodeClosed()` (called
by `BikeschedLifecycle.stop()`) turns beans created during or after shutdown into a debug log instead
of a failure. Registration scans `JobHandlers.scan(targetClass)`, binds each `AnnotatedJobHandler`
with `bindTo(instance)` and calls `registerOn(node)`, wrapped in a per-bean try/catch that rethrows
as `HandlerRegistrationException` naming the bean (§9). Before registering, every discovered handler
passes through the `HandlerWarning` beans (§8).

### 5.2 AOP proxies: the change that landed in `bikesched-annotations`

As the draft required, `bikesched-annotations` gained the additive, instance-independent API:

- `JobHandlers.scan(Class<?> targetClass)` → `List<AnnotatedJobHandler>` not yet bound to an
  instance;
- `JobHandlers.hasAnnotatedMethods(Class<?>)` — the cheap cached filter;
- `AnnotatedJobHandler.bindTo(Object target)` — resolves the invocable method on the target the way
  Spring's `AopUtils.selectInvocableMethod` does, so a CGLIB override (and its advice) runs and a
  JDK proxy is reachable through its interface method;
- `JobHandlers.register(bikesched, target, targetClass)` for the plain-object path.

The draft's `registerOn(Bikesched, String beanName)` overload exists (`source` names the handler in
the duplicate-type message), but the starter does not pass it: `JobHandlerBeans` calls plain
`registerOn(node)` and wraps the duplicate-type `IllegalArgumentException` in a
`HandlerRegistrationException` whose message names the bean anyway — the Spring-side diagnostic
(plus its `FailureAnalyzer`) belongs to the Spring module.

### 5.3 Registration semantics

- **No database access.** `registerJobHandler` only touches the node's in-memory registry
  (`JobRegistry`), so handlers can be registered before `startPolling()` and while polling is
  running. This is what makes the holder approach work.
- **Before polling.** Registration happens in the `bikesched` factory method, i.e. before
  `SmartLifecycle.start()` calls `startPolling()`. Polling claims only registered job types, so a node
  never claims work it cannot run.
- **Duplicates.** Two methods of one class handling the same job type are rejected by `scan`; across
  beans, `registerJobHandler` throws and the starter rethrows with the bean name, so a copy-pasted
  job type fails startup with a message naming the bean and the method. No property to downgrade
  this to a warning.
- **Beans, not classes.** Each bean with annotated methods is scanned; a prototype bean registers
  once, from the first instance. Documented, not guarded.
- **`JobTypeHandler` beans** (core's interface with `type()`) are registered after the annotated
  methods, so a `type()` collision with an annotated method fails startup with the same duplicate
  message.

## 6. Lifecycle

`BikeschedLifecycle` (`SmartLifecycle`, phase `Integer.MAX_VALUE` — last to start, first to stop):

```java
@Override public void start() {
  if (properties.migrateOnStartupEnabled()) bikesched.migrate();
  if (!properties.pollingEnabled()) { LOG.info("… not registered, call startPolling() …"); return; }
  bikesched.startPolling();
  running = true;
}
@Override public void stop() { jobHandlerBeans.nodeClosed(); running = false; } // close() by the bean's destroy method
```

- **Build** with `autoStartPolling(false)`: such a node registers itself with no database at all —
  no node row, no heartbeat, no migration — it only schedules. (This changed in core while
  implementing the starter: `build()` with polling disabled used to register the node immediately;
  now `startPolling()` does the registration.)
- **Start** from `SmartLifecycle.start()` (during `finishRefresh()`, before `ApplicationReadyEvent`),
  after every other lifecycle bean (web server, Flyway/Liquibase on a lifecycle, executors) is up.
  Polling is skipped when `bikesched.polling.enabled=false`; the application then calls
  `startPolling()` itself (e.g. from its own `ApplicationRunner` after seeding).
- **Migration is fail-fast and independent of polling**: `bikesched.migrate()` runs at startup when
  `bikesched.migrate-on-startup=true` (the default) *even when this instance never polls*, so a
  scheduling-only application also learns about a missing table, a `V001` checksum mismatch or an
  unreachable database at boot instead of at 3 a.m. — the draft's open question §13.2.1 resolved to
  "keep `true` for both". Deployments that must start before their database is reachable set
  `bikesched.migrate-on-startup=false` and keep the lazy behaviour (migration on first use).
  This is why the lifecycle bean is `@ConditionalOnBean(Bikesched)` only, not
  `@ConditionalOnProperty(polling.enabled)`.
- **Stop**: `Bikesched.close()` is called by Spring's destruction phase (destroy-method detection),
  *before* the `DataSource` it depends on; `stop()` only flags the holder so late beans are skipped
  (§5.1). Waiting for running jobs is `close()`'s behaviour, bounded by the job timeouts.
- **Multiple contexts / refresh**: `startPolling()` is idempotent (returns the node once registered).
- **Several nodes in one JVM**: each context creates its own node; names must differ (§7.1.1), and
  "A live bikesched node named 'x' is already registered" is the intended guard.

## 7. Properties, customizers, listeners, JSON

### 7.1 `bikesched.*` properties

`BikeschedProperties` is an immutable `@ConfigurationProperties("bikesched")` **record** with boxed
fields; `applyTo(BikeschedBuilder)` applies only the properties that are set, so an empty `bikesched`
section changes nothing and the builder owns the defaults (the draft's `RetryBackoffProperties`
became the nested `Backoff`/`Exponential` records of the same top-level record). Relaxed binding
maps kebab-case to the components.

| Property | Builder method | Default |
|---|---|---|
| `bikesched.node-name` | `nodeName(String)` | `<spring.application.name>-<host name>`, else the host name (§7.1.1) |
| `bikesched.data-source` | — (§4.1) | the single/`@Primary` `DataSource` |
| `bikesched.poll-interval` | `pollInterval(Duration)` | 5 seconds (new default, was 1 second) |
| `bikesched.heartbeat-interval` | `heartbeatInterval(Duration)` | 5 seconds |
| `bikesched.node-timeout` | `nodeTimeout(Duration)` | 30 seconds |
| `bikesched.max-concurrent-jobs` | `maxConcurrentJobs(int)` | 10 |
| `bikesched.polling.enabled` | `autoStartPolling(boolean)` (inverted) | `true` |
| `bikesched.default-job-timeout` | `defaultJobTimeout(Duration)` | none |
| `bikesched.default-event-timeout` | `defaultEventTimeout(Duration)` | none |
| `bikesched.retry-backoff.fixed` / `.exponential.{initial,max}` | `retryBackoff(RetryBackoff)` | `RetryBackoff.none()` |
| `bikesched.event-retention` | `eventRetention(Duration)` | 7 days |
| `bikesched.event-clean-up-interval` | `eventCleanUpInterval(Duration)` | 1 hour |
| `bikesched.node-clean-up-interval` | `nodeCleanUpInterval(Duration)` | 15 seconds |
| `bikesched.job-history-retention` | `jobHistory(Duration)` | off |
| `bikesched.job-history-clean-up-interval` | `jobHistoryCleanUpInterval(Duration)` | 1 hour |
| `bikesched.job-retention` | `jobRetention(Duration)` | 7 days |
| `bikesched.job-clean-up-interval` | `jobCleanUpInterval(Duration)` | 1 hour |
| `bikesched.migrate-on-startup` | — (§6) | `true` |
| `bikesched.metrics.enabled` | — (§7.4) | `true` with a `MeterRegistry` bean |
| `bikesched.statement-timeout` | `statementTimeout(Duration)` | data source |
| `bikesched.hard-timeout-grace` | `hardTimeoutGrace(Duration)` | off |

**`pollInterval`, `heartbeatInterval` and `nodeTimeout` became public builder options** (they were
package-private test hooks in `BikeschedBuilder`) and **the poll interval default rose from 1 to 5
seconds**, as decided: 1 second is fine for a single web app, but every node of a 50-node batch
cluster polls `bikesched_jobs` every second; 5 seconds stays sensible at scale, applications that
need sub-second reaction lower it, applications that only schedule never poll. `nodeTimeout` must
exceed `heartbeatInterval`; the builder validates it (§12.1). Consequences: the Kotlin DSL gained
the three properties for parity, `docs/configuration.md` has the three rows and the new default.

`bikesched.retry-backoff` is nested (`fixed` *or* `exponential` with `initial`/`max`); both set, a
half missing, or `retry-backoff:` present but empty fails the context with the property path in the
message (validated in `BikeschedProperties.backoff()` when applied).

#### 7.1.1 Node name default

The draft had `spring.application.name` as the default. Replicas share it — a second pod with the
same application name would hit "A live bikesched node is already registered" — so `NodeNames`
resolves the default to `<spring.application.name>-<host name>` (host name from
`InetAddress`, falling back to `$HOSTNAME`, then `bikesched`), and `bikesched.node-name` overrides
it. The Micrometer `node` tag (§7.4) uses the *resolved* name, not the raw property.

### 7.2 Customizers

```java
@FunctionalInterface
public interface BikeschedCustomizer { void customize(BikeschedBuilder builder); }
```

Applied via `ObjectProvider.orderedStream()` (so `Ordered`/`@Order`) after the properties, before
`build()`, so an application can set anything the properties do not cover (`jsonMapper`,
`jobListener`, a computed `statementTimeout`). This mirrors Spring Boot's own `…Customizer` contract.

### 7.3 JSON mapping

As drafted, through a nested `JacksonConfiguration` (`@ConditionalOnClass(JacksonJsonMapper)`), so
the Jackson types appear only inside guarded configuration:

```java
@Bean
@ConditionalOnMissingBean(JsonMapper.class)
JsonMapper jsonMapper(ObjectProvider<tools.jackson.databind.ObjectMapper> mapper) {
  var objectMapper = mapper.getIfAvailable();
  return objectMapper == null ? new JacksonJsonMapper() : new JacksonJsonMapper(objectMapper);
}
```

The `ObjectProvider` avoids ordering against `JacksonAutoConfiguration` (in `spring-boot-jackson`
since Boot 4) and behaves the same when the application defines no `ObjectMapper`. The `bikesched`
factory reads `ObjectProvider<JsonMapper>` and calls `builder.jsonMapper(…)` only when a bean exists,
so without `bikesched-jackson` the core default (JSON strings passed through) stays in charge. A
user-defined `JsonMapper` bean wins; Jackson 3 (`tools.jackson`) is what Boot 4 and our Jackson
module use.

### 7.4 Listeners and metrics

The builder takes one `JobListener`: the factory composes the context's `JobListener` beans through
`CompositeJobListener` (ordered by `Ordered`) when there is more than one, uses the single bean
as-is, and passes nothing when there are none.

With `bikesched-micrometer` on the classpath and a `MeterRegistry` bean, the nested
`MicrometerConfiguration` creates a `MicrometerJobListener` from that registry, tagged
`node` = the resolved node name (§7.1.1):

```java
return new MicrometerJobListener(registry, Tags.of("node", NodeNames.resolve(properties.nodeName(), environment)));
```

The `node` tag is on purpose: the meters include gauges, and two nodes sharing one registry would
overwrite each other's gauge otherwise (`metrics.md`'s manual advice, automated). Common tags stay
the registry's business. A user-defined `MicrometerJobListener` bean wins;
`bikesched.metrics.enabled=false` turns the automatic one off; without a `MeterRegistry` nothing
happens.

## 8. Transactions

- **Handlers**: `@Transactional` on a `@JobHandler` method works, because discovery binds through the
  proxy (§5.1). Handlers run on bikesched's virtual job threads, so transactions are autonomous:
  there is no ambient transaction to join and `REQUIRED` behaves as `REQUIRES_NEW` — which is what a
  job wants; the difference matters for retries (a rolled-back handler means the outcome recording
  happens outside the transaction).
- **The module warns about exactly that**: `TransactionPropagationWarning` (a `HandlerWarning`,
  §5.1) logs once per discovered handler method annotated — on the method, the class, or a composed
  annotation, via `MergedAnnotations` — `@Transactional` with the default propagation:

  > bikesched: handler method … is @Transactional with the default propagation REQUIRED, but job
  > handlers run without an ambient transaction: this starts a new transaction, equivalent to
  > REQUIRES_NEW. Make it explicit with @Transactional(propagation = REQUIRES_NEW) to document the
  > intent.

  An explicit other propagation is silent; the warning is a hint, not a failure, logging level is
  the knob. It only sees annotations — a handler using `TransactionTemplate` gets no hint; accepted
  limitation, worth a line in `docs/spring.md` (§13.2).
- **Step results and job state** are written by bikesched on its own connections, outside the
  handler's transaction — the documented `steps.md` semantics, not a Spring concern.
- **Scheduling inside a caller's transaction** (the classic "schedule the job only if my transaction
  commits"): `BikeschedTransactions.transactionalConnection(dataSource)` returns the caller's
  transaction-bound connection — or `null` when no transaction is active *or* the `DataSource` is
  not bound to it, so the builder path stays uniform — and the caller passes it to
  `connection(Connection)` itself; Spring releases it with the transaction and the insert rolls back
  with it. No builder wrapping, no AOP interception (out of scope). Recurring jobs cannot be
  scheduled this way: their registration lives in the node's memory. Needs `spring-tx`/`spring-jdbc`
  (hence `requires static`). `FulfillmentService` in the example (§12.3) publishes an event the same
  way, on the transaction that stores its row.

## 9. Diagnostics

- One startup log at info from the factory: node name, number of registered `@JobHandler` methods,
  listener count, `JsonMapper` class ("the default JSON mapper (JSON strings only)" without one);
  debug per registered handler (job type, bean, method); `BikeschedLifecycle` logs the migration
  (debug), the polling start (info, with node id) and the `polling.enabled=false` case (info, what
  to call to start later); a late bean after `nodeClosed()` is a debug log (§5.1).
- Errors that fail the context: duplicate job type / invalid `@JobHandler` signature
  (`HandlerRegistrationException`, naming bean and method, §5.3), the `DataSource` selection
  failures (§4.1, `DataSourceSelectionException` naming the candidates), an invalid property
  (standard Boot `BindingResult`), `node-timeout ≤ heartbeat-interval` (the builder's validation).
  The first two exception types get `FailureAnalyzer`s registered in `META-INF/spring.factories`,
  so the message reads as advice — "bikesched found 2 DataSource beans (a, b) and none is @Primary
  — set bikesched.data-source to pick one" — instead of a stack trace.
- A startup migration failure (§6) surfaces with the database URL and the failed migration; the
  transaction warning (§8) is `WARN` once per handler method.
- Docs page `docs/spring.md` (developer docs, not this spec): quick start, the property table, the
  handler rules with a proxy note, transactions, and the "one node per JVM name" caveat — §13.2.

## 10. Risks and sharp edges

| Risk | Handling |
|---|---|
| BPP ordering: our BPP must run before beans are used but must not depend on `Bikesched` | collect only; register from the factory method and on later bean creation (§5.1) |
| A bean is proxied before our BPP sees it | `postProcessAfterInitialization` at `LOWEST_PRECEDENCE` sees the finished proxy: `ultimateTargetClass` for scanning, `bindTo` for invoking through it; tests cover both JDK and CGLIB proxies |
| `@Lazy`/prototype beans whose handlers never register | register on creation (§5.1); a job of such a type simply waits until the bean exists; log at debug |
| Two contexts in one JVM (tests) share a node name | default name includes the host name (§7.1.1); tests set `bikesched.node-name` |
| Two `DataSource` beans, none selected | `bikesched.data-source` (§4.1); without it the context fails with the `FailureAnalyzer` message, never with a silently chosen database |
| Raising the poll interval to 5 seconds delays a freshly scheduled job by up to 5 seconds | documented in `docs/configuration.md` and `jobs.md` ("Scheduling"); a node that needs lower latency lowers the interval |
| Application shuts down while jobs run | `close()` releases running jobs and waits for the job threads; `Bikesched` is destroyed before the `DataSource` thanks to the dependency |
| A `@JobHandler` method on a bean that is also a `JobListener` or defines `@Bean` methods | no special handling; the type registry is the only contract |
| Users expect `@Scheduled`-like cron | explicitly out of scope, documented in §2 and the docs page |

## 11. Testing (as implemented)

1. **Conditions** (`ApplicationContextRunner`) — `BikeschedAutoConfigurationTest`, extending
   `AbstractSpringTest` (one shared Testcontainers PostgreSQL 18 for the module's JVM, as in the
   core tests; the runner gets the plain test `DataSource` bean, so `spring-boot-starter-jdbc` is
   not needed on the test classpath): no `DataSource` → no `Bikesched` bean; `DataSource` present →
   `Bikesched` bean whose node is registered and polling after refresh;
   `bikesched.polling.enabled=false` → node `isPolling() == false`; a user-defined `Bikesched` bean
   wins; a `BikeschedCustomizer` is applied; `bikesched.*` properties bind (relaxed names,
   `Duration` suffixes, nested `retry-backoff`, `poll-interval`); a `retry-backoff` setting both
   `fixed` and `exponential` fails; several `DataSource` candidates without the property → the
   context fails with the §4.1 message; `@Primary` among several → used, others untouched
   (`RecordingDataSource` wraps the delegates and counts opened connections);
   `bikesched.data-source=…` → the named one is used; the property naming a missing bean or a
   non-`DataSource` bean → fails naming it; `node-timeout ≤ heartbeat-interval` → fails with the
   builder's validation message.
2. **Handler discovery**, real PostgreSQL (Testcontainers) — `JobHandlerDiscoveryTest`: `@JobHandler`
   bean end to end; the signature variants (`Job`, payload, both, none) and a `JobTypeHandler` bean;
   a `@Transactional` bean proxied JDK (`proxyTargetClass=false`) **and** CGLIB (`true`): found,
   run through the advice, rollback visible; duplicate job types across two beans → failure naming
   the bean; invalid signature → naming the method; a `@Lazy` bean created after startup registers
   and its job runs.
3. **Lifecycle** — `LifecycleTest`: context close → the node's `bikesched_nodes` row is gone
   (destroyed before the `DataSource`); `polling.enabled=false` → the schema *is* migrated but no
   node row is registered (§6's fail-fast-without-polling); `migrate-on-startup=false` against a
   fresh database → no tables at startup, the first scheduled job migrates lazily.
4. **Listeners and metrics** — `ListenersAndMetricsTest`: two `JobListener` beans both receive calls;
   with `bikesched-micrometer` and a registry, the automatic listener records with the `node` tag;
   `bikesched.metrics.enabled=false` leaves the registry empty; a user-defined
   `MicrometerJobListener` wins; no `MeterRegistry` → no listener.
5. **JSON** — `JsonMappingTest`: record payload with `bikesched-jackson`; a user `JsonMapper` bean
   wins.
6. **Transaction warning** — `TransactionWarningTest`: a method-level and a class-level default
   `@Transactional` each warn once (via the `Logs` helper); an explicit propagation stays silent.
7. **No Spring in core/annotations**: guaranteed by the build — neither module declares a Spring
   dependency — and `ArchitectureTest` keeps its existing rules for the core packages; the spring
   module's own package boundaries are plain JPMS (`exports org.bikesched.spring` only). No
   ArchUnit rule was added for it.
8. **End-to-end example** (§12.3): `SpringExampleTest` covers auto-configuration, discovery and
   shutdown in one CI run.

## 12. Implementation (landed)

### 12.1 Prerequisite changes in other modules

1. `bikesched-core`: public `pollInterval`, `heartbeatInterval`, `nodeTimeout` builder options;
   `DEFAULT_POLL_INTERVAL` 1s → 5s; `nodeTimeout > heartbeatInterval` validation in `build()`.
2. `bikesched-core`: `Bikesched.migrate()`, the migration-only entry point for the fail-fast startup
   (§6); `autoStartPolling(false)` nodes do no database work until `startPolling()` (§6).
3. `bikesched-kotlin`: the three DSL properties for parity.
4. `bikesched-annotations`: `JobHandlers.scan(Class)`, `hasAnnotatedMethods(Class)`,
   `AnnotatedJobHandler.bindTo(Object)` — proxy-safe scanning and binding (§5.2) — with tests.
5. Docs: `docs/configuration.md` (three builder rows, the 5s default, the scheduling delay note).

### 12.2 The Spring module

Skeleton (build files, catalog entries, `module-info.java`, `AutoConfiguration.imports`) →
properties + `DataSourceSelector` + customizers + node + lifecycle + `FailureAnalyzer`s → discovery
(BPP + holder + `JobTypeHandler` beans + duplicate diagnostics) → `JsonMapper`, listener composition,
`MicrometerJobListener` → transaction helper + propagation warning → docs (§13.2). The module
publishes like the others (`bikesched.publishing`); no workflow change.

### 12.3 Example application in `bikesched-examples`

Package `org.bikesched.examples.spring`, task `./gradlew :bikesched-examples:runSpring`,
following the conventions of the other examples. The scenario is deliberately **two microservices
that share no data** in one JVM: `OrderService` and `FulfillmentService` communicate only through a
bikesched job payload and a keyed event, and each owns its own JPA entity (`Order`, `Fulfillment`) —
`Fulfillment.orderId` is a plain `UUID` column, **no foreign key, no association**, because a real
fulfillment database would not even see the orders table.

- `SpringExampleApplication` (`@SpringBootApplication`): a `@Bean DataSource dataSource()` returning
  `DatabaseUtil.composeDatabase()` — the same compose database as every other example (honoring
  `POSTGRES_PORT`), the Spring way: a bean, not `spring.datasource.*` properties; and an
  `ApplicationRunner` that places one order via `OrderService`, waits for its job to end
  (Awaitility, as in the other examples), prints the state and closes the context, so `runSpring`
  terminates like the others instead of hanging on a non-web Boot app.
- `OrderService` (`@Service`, the saga): `placeOrder(customer, item)` saves the `Order` and
  schedules the `fulfill-order` job through
  `connection(BikeschedTransactions.transactionalConnection(dataSource))`, so order row and job
  commit together or not at all; its `@JobHandler(type = "fulfill-order")` method — the starter
  discovers it — runs `job.step("fulfill", UUID.class, …)` (the fulfillment id is the durable step
  result), suspends at `job.awaitEvent(FULFILLMENT_FINISHED, fulfillmentId)` and then marks the
  order fulfilled.
- `FulfillmentService` (`@Service`, the "remote" service): on a virtual thread, inside a
  `TransactionTemplate`, stores the `Fulfillment` **and** publishes the `fulfillment-finished` event
  (keyed by the fulfillment id) on the same transaction connection — so the job only resumes when
  the fulfillment row actually exists; the step/event pair replays safely after a restart.
- `application.properties`: `spring.jpa.hibernate.ddl-auto=update`, `bikesched.node-name=spring-example`,
  `bikesched.poll-interval=1s` — two `bikesched.*` properties showing what the starter adds (§7.1),
  and the absence of `spring.datasource.*` showing that the `DataSource` stays the application's.
- **Build**: `implementation(project(":bikesched-spring-boot-starter"))` plus
  `spring-boot-starter-data-jpa` (the entities need JPA; its transitive `spring-boot-starter-jdbc`
  is the pooled-DataSource/Hibernate plumbing) and a `runSpring` `JavaExec` task like the other
  `run…` tasks.
- **Test** `SpringExampleTest`: `SpringApplicationBuilder` against the shared Testcontainers database
  — plain `SpringApplication` + JUnit, deliberately not `@SpringBootTest` (which would pull
  `spring-boot-starter-test`'s Mockito/JSONassert into the examples module for one assertion). A
  `TestConfiguration` adds a `@Primary` `DataSource` bean (`Database.dataSource()`); being `@Primary`
  it wins the selection of §4.1 over the application's compose bean without overriding its bean
  definition (Boot forbids definition overriding), which also exercises the `@Primary` path.
  `spring.jpa.hibernate.ddl-auto=create` via properties. Asserts the runner closed the context,
  then counts: exactly one fulfilled order, one fulfillment.

Because this example runs in CI on every build, it is the permanent end-to-end proof of the starter
(auto-configuration, handler discovery, lifecycle, shutdown, the transaction helper from both sides,
steps and event waits) — stronger than the one-off manual snapshot consumption check (§12.2), which
caught publishing/consumption mistakes.

## 13. Decisions

### 13.1 Decided (draft) — all confirmed by the implementation

| Question | Decision |
|---|---|
| Public builder options | `pollInterval`, `heartbeatInterval` and `nodeTimeout` become public; poll default 1s → 5s (§7.1) |
| Module layout | one module, artifact `bikesched-spring-boot-starter`, package + JPMS module `org.bikesched.spring`; a later `-autoconfigure` split stays transparent (§3) |
| Handler discovery | Option A: `BeanPostProcessor` collecting candidates + `JobHandlerBeans` holder registering before polling (§5) |
| Schema migration | fail-fast: `Bikesched.migrate()` in core, `bikesched.migrate-on-startup=true` by default (§6) |
| Micrometer | supported from v1: automatic `MicrometerJobListener` from a `MeterRegistry` bean, tagged with the node name, `bikesched.metrics.enabled` to turn it off (§7.4) |
| `RetryBackoff` properties | nested properties (`fixed`, `exponential.initial`/`exponential.max`) as sketched in §7.1 |
| Transaction semantics | document `REQUIRED == REQUIRES_NEW` for handlers *and* warn once per handler method that uses the default propagation (§8) |
| `DataSource` selection | `bikesched.data-source` names the bean; unique/`@Primary` otherwise (§4.1) |

### 13.1.1 Decided while implementing (differs from the draft)

| Question | Decision |
|---|---|
| `migrate-on-startup` with `polling.enabled=false` (draft §13.2.1) | fail-fast stays unconditional: migration runs at startup even for scheduling-only instances; `migrate-on-startup=false` opts out (§6) |
| `autoStartPolling(false)` semantics | the node registers with no database until `startPolling()` — previously it registered at `build()` (§6) |
| Default node name | `<spring.application.name>-<host name>`, not the application name alone: replicas must not collide (§7.1.1) |
| Properties representation | one immutable `@ConfigurationProperties` record with boxed fields, `applyTo(builder)` applying only set values; no mutable class, no separate `RetryBackoffProperties` (§7.1) |
| Bean name in the duplicate message | via the starter's `HandlerRegistrationException`, not a `registerOn(Bikesched, String)` overload in annotations (§5.2) |
| Optional integrations guarded by nested `@ConditionalOnClass` `@Configuration` classes (Jackson, Micrometer, `spring-tx`) instead of guarded methods, so optional types stay off the main configuration's signatures (§3, §7) |
| `spring.factories` | used, but only for the `FailureAnalyzer` registrations (§3, §9) |
| Example scenario | the order/fulfillment microservice pair of §12.3, not the draft's print-only `MailJobs`; the `@JobHandler` lives on `OrderService` itself, the `DataSource` is a `DatabaseUtil` bean, not `spring.datasource.*` properties (§12.3) |

### 13.2 Resolved during the docs change

1. **Limits of the transaction warning**: accepted as a documented limitation — `docs/spring.md`
   ("Transactional handlers") says that annotation-less transaction management
   (`TransactionTemplate`, repository calls) gets no hint and how to write handlers explicitly.
2. `docs/spring.md`, the module rows in `README.md`/`AGENTS.md`/`bikesched-examples/README.md`
   (plus the examples `runSpring` line and the snapshot artifact list), the ADR
   [`0022`](../decisions/0022-spring-integration-in-its-own-module.md) listed in
   `docs/decisions/README.md`, the `docs/index.md` entry, the `docs/annotations.md` "planned"
   note, the three builder rows and the 5-second polling default in `docs/configuration.md`, the
   scheduling-delay sentence in `docs/jobs.md`, `Bikesched.migrate()` in `docs/database.md` and
   the removal of the `IDEAS.md` entry have landed.

## 14. Alternatives considered

- **Spring beans as handlers via an interface** (`class X implements JobTypeHandler`), no annotation
  scanning: less magic, but the annotation is already the project's mechanism and reads better for
  DI-heavy code (`@JobHandler(type = "send-invoice")` next to `@Transactional`). Both are supported:
  `JobTypeHandler` beans are registered next to annotated methods (§5.3).
- **`BeanFactoryPostProcessor`/classpath scanning of `@JobHandler` types** (Spring's
  `ClassPathScanningCandidateComponentProvider`): finds types that were never registered as beans,
  and would register handlers for beans the application deliberately did not create; also needs the
  package list. Rejected, and the naming collision with `@Component`-scanned classes is a real hazard.
- **`SmartInitializingSingleton` registrar** (draft Option B): simpler, but `@Lazy`/prototype beans
  are silently missed and `getType(name)` fails for some factory definitions. Rejected in favour of
  the collecting BPP.
- **Registering handlers in `ContextRefreshedEvent`** (draft Option C): runs after
  `SmartLifecycle.start()`, leaving a polling window with missing handlers. Rejected.
- **Proxy handling inside the starter or `proxyTargetClass = true`** (draft §5.6 alternatives):
  duplicated the validation logic or constrained users; the additive annotations API won.
- **Full Spring integration in core** (`spring-context` as an optional dependency): violates the
  "core stays free of concrete libraries" rule in `AGENTS.md`.
- **Node name default `spring.application.name` alone**: collides on replicas (§7.1.1).
- **Example as one print handler** (this spec's draft §12.3): passes, but teaches nothing about jobs
  *plus* events *plus* transactions in a Spring context, and the starter's transaction helper was
  unexercised. The order/fulfillment scenario of §12.3 replaced it.
