# Specification: `bikesched-spring-boot-starter`

Status: draft, not implemented. Target: Spring Boot 4.1.x (Spring Framework 7, Java 17 bytecode, runs
on the project's Java 26). Depends on `bikesched-core` and `bikesched-annotations` only; core stays
free of Spring. A new ADR ("Spring integration in its own module") accompanies the implementation,
after this spec is settled.

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
| `spring-boot-autoconfigure` | `api` | `@AutoConfiguration`, conditions, `@ConfigurationProperties` |
| `spring-context`, `spring-beans`, `spring-core` | transitively via `spring-boot-autoconfigure` | — |
| `bikesched-jackson` | `compileOnly` + test | only for the optional Jackson `JsonMapper` default (§7.3); must stay optional |
| `bikesched-micrometer` | `compileOnly` + test | only for the optional `MicrometerJobListener` bean (§7.4) |
| `spring-boot-starter-test`, `postgresql`, Testcontainers | test | §11 |

Boot 4 modularized its own auto-configurations: `DataSourceAutoConfiguration` lives in
`spring-boot-jdbc` (`org.springframework.boot.jdbc.autoconfigure`) and `JacksonAutoConfiguration` in
`spring-boot-jackson` (`org.springframework.boot.jackson.autoconfigure`). We depend on neither;
`bikesched-spring-boot-starter` orders itself against the DataSource auto-configuration *by name* (§4) and takes
the `ObjectMapper`, if there is one, through an `ObjectProvider` (§7.3).

Version catalog entries: `spring-boot = "4.1.1"` (or the newest stable at implementation time), with
`spring-boot-autoconfigure` and `spring-boot-starter-test` from the same version. Tests need
`spring-boot-test` for `ApplicationContextRunner`. `spring-boot-autoconfigure` brings Spring
Framework 7.0.9 (what `spring-boot-dependencies` 4.1.1 manages), so the catalog pins only
`spring-boot`; no Spring version literal appears in the build. Verified against Boot 4.1.1: the
auto-configuration imports file is still
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, `@AutoConfiguration`
has `beforeName`/`afterName` next to `before`/`after`, `@ConditionalOnSingleCandidate` is still in
`org.springframework.boot.autoconfigure.condition`, and `AopProxyUtils.ultimateTargetClass`,
`AopUtils.selectInvocableMethod`, `AopUtils.getTargetClass` and `AopProxyUtils.getSingletonTarget`
are the available `spring-aop` helpers.

`module-info.java`: `open module org.bikesched.spring` (Spring needs deep reflection for binding and
bean post-processing; an open module avoids a list of `opens … to …` that breaks whenever Spring
touches another package). It `requires` the automatic modules `spring.context`,
`spring.boot.autoconfigure` and `spring.beans` with the `requires-automatic` lint suppressed, exactly
as `bikesched-micrometer` does for Micrometer. `exports org.bikesched.spring` so the
auto-configuration class can be loaded by name.

Auto-configuration registration:
`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
containing `org.bikesched.spring.BikeschedAutoConfiguration` (verified to be the file Boot 4.1.1
still reads). No `spring.factories` entry.

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
      ObjectProvider<BikeschedCustomizer> customizers, ObjectProvider<JobListener> listeners,
      ObjectProvider<JobTypeHandler<?>> handlers, JobHandlerBeans jobHandlerBeans,
      ObjectProvider<JsonMapper> jsonMapper) { … }
}
```

| Bean | Conditions | Notes |
|---|---|---|
| `Bikesched` | `@ConditionalOnClass(Bikesched)`, `@ConditionalOnBean(DataSource)`, `@ConditionalOnMissingBean` | built with `autoStartPolling(false)`; closed by Spring (its `close()` is detected as a destroy method), so it is destroyed before the `DataSource` it depends on |
| `DataSourceSelector` (`@Bean`, package-private) | — | resolves *which* `DataSource` (§4.1); deliberately not a `DataSource` bean, so it never becomes a candidate for other auto-configurations |
| `BikeschedProperties` | `@EnableConfigurationProperties` | §7.1 |
| `BikeschedLifecycle` (`SmartLifecycle`) | `@ConditionalOnBean(Bikesched)`, `@ConditionalOnProperty(bikesched.polling.enabled = true, matchIfMissing = true)` | starts polling after the context is refreshed (§6) |
| `JobHandlerBeanPostProcessor` | `@ConditionalOnClass(JobHandler)` + static `@Bean` factory method | collects candidate beans (§5) |
| `JobHandlerBeans` (holder) | as a `static @Bean` of the same configuration, so both the BPP and the `Bikesched` factory can see it | §5.5 |
| `JsonMapper` | `@ConditionalOnClass(JacksonJsonMapper)`, `@ConditionalOnMissingBean(JsonMapper)` | from the context's `ObjectMapper` if there is one, else `JacksonJsonMapper`'s own (§7.3) |
| `CompositeJobListener` | with more than one `JobListener` bean | §7.4 |
| `MicrometerJobListener` | `@ConditionalOnClass(MicrometerJobListener)`, `@ConditionalOnBean(MeterRegistry)`, `@ConditionalOnMissingBean(MicrometerJobListener)`, `bikesched.metrics.enabled` | from the context's `MeterRegistry`, tagged with the node name (§7.4) |

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

`DataSourceSelector` is a small package-private component that resolves the bean once, at startup:

| `bikesched.data-source` | Resolution |
|---|---|
| unset | the unique `DataSource` bean, or the `@Primary` one among several |
| set | the bean with that name, which must exist and be a `DataSource` |

Failure modes, all at startup and all naming the candidates: the property names a bean that does not
exist or is not a `DataSource`; several `DataSource` beans exist and none is `@Primary`; the property
is set and the named bean is not the `@Primary` one (that is allowed, the property wins — but it is
logged at info level, since silently picking a non-primary `DataSource` is worth knowing about). The
value is a Spring *bean name* (`ordersDataSource`), never a JDBC URL or a schema: the property picks
one of the `DataSource` beans the application already defines, and it must be spelled as the bean
name is (`@Bean DataSource ordersDataSource()` → `ordersDataSource`).
Everything bikesched stores — jobs, steps, events, nodes, history — lives in that database; the
schema is created there (§6).

This is *not* multi-tenancy: one node uses one `DataSource`. Two applications on the same database
with different node names is the supported way to scale, and one application wanting two independent
schedulers needs two contexts (or two node beans), which the auto-configuration's
`@ConditionalOnMissingBean` supports by backing off.

## 5. Handler discovery — the main design question

### 5.1 Requirements

1. Find every bean that has `@JobHandler` methods, including **AOP-proxied** beans (Spring's
   `@Transactional`, `@Async`, `@Cacheable`, Spring Security), and invoke the method **through the
   proxy** so the advice applies.
2. Do not instantiate beans early, and do not force `DataSource`/`Bikesched` creation from inside a
   bean post-processor (a `BeanPostProcessor` that injects infrastructure beans breaks
   auto-configuration ordering — the classic Boot pitfall).
3. Handlers must be registered before polling starts, so a node never claims a job whose handler
   arrives later, and never *fails* one for a missing handler (§5.7 explains why this matters in a
   rolling deploy).
4. Beans created after startup (`@Lazy`, prototype, custom scopes) should not be lost, or at least
   fail loudly instead of silently doing nothing.

### 5.2 Option A — `BeanPostProcessor` (recommended, with a twist)

Spring itself does this for `@Scheduled` (`ScheduledAnnotationBeanPostProcessor`): a BPP sees every
bean exactly once, at a point where the *target class* is still resolvable via
`AopProxyUtils.ultimateTargetClass(bean)`, and a cheap filter decides whether the class has annotated
methods at all (cached per class, so the reflective cost is one method scan per class in the whole
application).

The twist: our BPP must not touch the `Bikesched` bean (requirement 2). So it does not register
anything itself, it only **records candidates**:

```java
public class JobHandlerBeanPostProcessor implements BeanPostProcessor {

  private final JobHandlerBeans candidates; // shared holder, created by a static @Bean method

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
    if (JobHandlerBeans.hasAnnotatedMethods(targetClass)) {   // cached per class
      candidates.add(beanName, bean, targetClass);
    }
    return bean; // never wrapped, never modified
  }
}
```

`JobHandlerBeans` holds the candidates and registers them once a node exists:

```java
public final class JobHandlerBeans {

  private final Map<String, Object> beans = new ConcurrentHashMap<>();  // bean name → bean
  private volatile Bikesched bikesched;                                  // set by the factory

  void add(String beanName, Object bean, Class<?> targetClass) { …; registerIfNodePresent(beanName); }

  /** Called by the Bikesched factory bean after the node is built. */
  void registerAll(Bikesched node) {
    this.bikesched = node;
    beans.forEach(this::register);
  }

  private void register(String beanName, Object bean) {
    List<AnnotatedJobHandler> handlers = JobHandlers.scan(AopProxyUtils.ultimateTargetClass(bean))
        .stream().map(handler -> handler.bindTo(bean)).toList();   // §5.6
    handlers.forEach(handler -> handler.registerOn(bikesched, beanName));  // §5.7
  }
}
```

Order of events: the BPP collects every bean created during refresh (including the ones created
before `Bikesched`), the `Bikesched` `@Bean` method calls `jobHandlerBeans.registerAll(node)` after
building the node with `autoStartPolling(false)`, and every bean created *later* is registered
immediately by `add(…)`, because the node reference is set by then. Beans created after
`Bikesched` closes (context shutdown, or a late `@Lazy` use) are logged and skipped rather than
failing the application.

### 5.3 Option B — `SmartInitializingSingleton` registrar

Spring's `EventListenerMethodProcessor` takes this route: after all singletons are instantiated,
iterate `beanFactory.getBeanNamesForType(Object.class)`, resolve types with `beanFactory.getType(name)`
(avoiding early instantiation), filter by "has `@JobHandler` methods", and register.

Simpler (no holder, no BPP, no ordering puzzle) and it naturally runs before `SmartLifecycle` start
requests, so handlers are registered before polling starts. Costs:

- beans created later (`@Lazy`, prototypes) are silently missed — the failure mode is a job that
  never runs and is eventually claimed by a node without a handler (`FAILED`, "No handler registered
  for job type …"), which is exactly the production symptom we want to avoid;
- iterating all bean names and reflecting on every type at startup (mitigated by the same per-class
  cache, but the *filter* itself runs for every bean type);
- `getType(name)` returns `null` for some factory-method/FactoryBean definitions, so those beans need
  `getBean(name)` (early instantiation) or must be given up on.

### 5.4 Option C — `ApplicationListener<ContextRefreshedEvent>`

Even simpler, and the wrong place: it runs after `finishRefresh()`, i.e. after `SmartLifecycle.start()`,
so polling would already have started — a window in which the node claims jobs of types whose
handlers are not registered yet. Rejected.

### 5.5 Recommendation

**Option A.** It satisfies all four requirements, matches how Spring handles its own annotation-driven
callbacks, and the only cost is the `JobHandlerBeans` holder. C's ordering problem and B's blind spot
for late beans are both real. If the holder is judged too clever for v1, the fallback is B *plus*
starting polling from an `ApplicationListener<ApplicationReadyEvent>`, accepting that late beans are
unsupported; I prefer A.

### 5.6 AOP proxies: the one change needed in `bikesched-annotations`

`JobHandlers.scan(Object target)` walks `target.getClass()` and binds the found methods to `target`
with `MethodHandle`. For a **JDK dynamic proxy**, `target.getClass()` is the generated proxy class,
which extends `java.lang.reflect.Proxy` and implements the interface: the target class's annotated
methods are invisible, and a `MethodHandle` for the target-class method cannot be bound to the proxy.
CGLIB proxies happen to work (the annotated superclass method is found while walking the hierarchy,
and the handle dispatches virtually to the proxy override), which is the worst kind of situation: it
works until someone switches a bean to an interface-based proxy.

So `bikesched-annotations` gains a small, additive API:

- `JobHandlers.scan(Class<?> targetClass)` → `List<AnnotatedJobHandler>` whose handler is *not yet
  bound* to an instance (the signature/validation rules are unchanged);
- `AnnotatedJobHandler.bindTo(Object target)` → returns a handler bound to that instance, resolving
  the invocation as Spring's `AopUtils.selectInvocableMethod` does: prefer the method of
  `target.getClass()` with the same name and parameter types (so a CGLIB proxy override, and its
  advice, run), else the method as found (works for plain objects and non-proxied beans);
- `registerOn(Bikesched)` stays and keeps working for the unbound `scan(Object)` path; the
  instance-based overload `registerOn(Bikesched, String beanName)` exists so the Spring module can
  report *which bean* registered a duplicate job type.

Alternatives considered: (a) scanning the proxy class and falling back to reflective
`Method.invoke` — loses `MethodHandle` exceptions-transparency only marginally, but duplicates the
binding logic; (b) doing the reflection in `bikesched-spring-boot-starter` and skipping `JobHandlers` — duplicates
signature validation, and the two would drift; (c) requiring `proxyTargetClass = true` — unacceptable
for users. Rejected all three.

This is the only change to an existing module the spec asks for. It is additive: no signature
changes, no behaviour change for existing callers, so it can land with the Spring module in one
commit chain, and `bikesched-annotations` keeps depending on core only.

### 5.7 Registration semantics

- **No database access.** `registerJobHandler` only touches the node's in-memory registry
  (`JobRegistry`), so handlers can be registered before `startPolling()` and while polling is
  running. This is what makes the holder approach work.
- **Before polling.** Registration happens in the `Bikesched` factory method, i.e. before
  `SmartLifecycle.start()` calls `startPolling()`. Polling claims only registered job types, so a node
  never claims work it cannot run.
- **Duplicates.** `JobHandlers.scan(Object)` rejects two methods of one object handling the same job
  type; across beans, `registerJobHandler` throws `IllegalArgumentException("A handler for job type
  'x' is already registered")`. The Spring module catches that and rethrows with the
  `@JobHandler`-declaring bean names, so a copy-pasted job type fails startup with a message naming
  both beans. No property to downgrade this to a warning.
- **Beans, not classes.** Each bean with annotated methods is scanned; a bean is bound to the first
  instance created for its class (a prototype bean therefore registers once, from the first
  instance). Documented, not guarded: applications wanting per-instance handlers should use
  `registerJobHandler` themselves.
- **`JobTypeHandler` beans** (core's interface with `type()`) are registered as well, so
  class-per-handler style code keeps working next to `@JobHandler` methods. Both are documented; a
  `JobTypeHandler` bean whose `type()` collides with an annotated method fails startup with the same
  duplicate message.

## 6. Lifecycle

```java
public class BikeschedLifecycle implements SmartLifecycle {
  private volatile boolean running;
  @Override public void start() { bikesched.startPolling(); running = true; }   // idempotent
  @Override public void stop() { running = false; }                             // close() by the bean's destroy method
  @Override public boolean isRunning() { return running; }
  @Override public int getPhase() { return Integer.MAX_VALUE; }                 // last to start, first to stop
}
```

- **Build** with `autoStartPolling(false)`: `build()` then touches no database, so an application
  whose database is briefly unreachable still starts, and the schema is migrated on `startPolling()`.
- **Start** from `SmartLifecycle.start()` (during `finishRefresh()`, before `ApplicationReadyEvent`),
  with `getPhase() == Integer.MAX_VALUE` so polling starts after every other lifecycle bean (web
  server, Flyway/Liquibase on a lifecycle, executors) is up. Skipped when
  `bikesched.polling.enabled=false`; the application then calls `startPolling()` itself (e.g. from its
  own `ApplicationRunner` after seeding).
- **Stop**: `Bikesched.close()` is called by Spring's destruction phase, because the bean is created
  by a `@Bean` method whose `close()` Spring detects as the destroy method. This is what we want:
  `Bikesched` depends on the `DataSource`, so it is destroyed *before* it. `stop()` therefore only
  clears `running`, and `stop(Runnable)` (SmartLifecycle's callback variant) is not used: waiting for
  running jobs is bikesched's `close()` behaviour, bounded by the job timeouts.
- **Multiple contexts / refresh**: `startPolling()` is idempotent (it returns the node if it is
  already registered), so a `ContextRefreshedEvent` after a manual `startPolling()` is harmless.
- **Several nodes in one JVM** (integration tests, or two apps in one JVM): each context creates its
  own node; names must differ (`bikesched.node-name`, default below), and the
  "A live bikesched node named 'x' is already registered" failure is the intended guard.
- **Schema migration is fail-fast** (decided): with `bikesched.migrate-on-startup=true` (the default)
  the node migrates and validates the schema during startup, *before* polling would start and
  regardless of `bikesched.polling.enabled`, so a missing table, a checksum mismatch of `V001` or a
  database that does not answer fails the context with a clear error instead of failing the first job
  at 3 a.m. This needs a migration-only entry point in core (`Bikesched.migrate()`, §12.1), since
  today migration happens as a side effect of the first database access. With
  `bikesched.migrate-on-startup=false` the previous lazy behaviour is kept for deployments that start
  before their database is reachable.

## 7. Properties, customizers, listeners, JSON

### 7.1 `bikesched.*` properties

Every public builder option gets a property; relaxed binding maps kebab-case to Java names.
Defaults are bikesched's own (so an empty `bikesched` section changes nothing).

| Property | Builder method | Default |
|---|---|---|
| `bikesched.node-name` | `nodeName(String)` | `spring.application.name`, else the local host name |
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

**Decision: make `pollInterval`, `heartbeatInterval` and `nodeTimeout` public builder options** (they
are package-private test hooks in `BikeschedBuilder` today) and **raise the poll interval default from
1 second to 5 seconds**. Rationale: the poll interval is the first thing an application will want to
change — 1 second is fine for a single web app, but every node of a 50-node batch cluster polls the
`bikesched_jobs` table every second, and the load is proportional to nodes. 5 seconds keeps the
default sensible at scale while a job scheduled "now" still starts within 5 seconds; applications
that need sub-second reaction lower it explicitly, and applications that only schedule jobs
(`bikesched.polling.enabled=false`) never poll at all. The tests' 50 ms poll interval keeps working
because `testNode` sets it explicitly.

The other two are exposed for the same reason as `node-clean-up-interval`: a node timeout of 30
seconds assumes a 5-second heartbeat, which is wrong for a cluster with slow nodes or long GC pauses.
`nodeTimeout` must be greater than `heartbeatInterval` (with a healthy node missing a heartbeat and a
dead node being detected as a trade-off in between); the builder validates it instead of leaving a
misconfigured node to release other nodes' jobs.

Consequences outside this module (see §12.1): the Kotlin DSL gains the three properties for parity,
`docs/configuration.md` gains the three rows of its builder-options table and the new poll-interval
default, and `docs/kotlin.md`'s DSL table stays as it is (it lists the DSL entry point, not every
property).

`RetryBackoff` is a nested structure (`fixed` or `exponential` with `initial`/`max`), bound through a
small `RetryBackoffProperties`; both forms are mutually exclusive and validated at binding time.

### 7.2 Customizers

```java
@FunctionalInterface
public interface BikeschedCustomizer { void customize(BikeschedBuilder builder); }
```

Applied in `Ordered`/`@Order` order after the properties, so an application can set anything the
properties do not cover (`jsonMapper`, `jobListener`, or a `statementTimeout` computed from the
environment). This mirrors Spring Boot's own `…Customizer` contract.

### 7.3 JSON mapping

With Jackson on the classpath (`bikesched-jackson`), a `JsonMapper` bean is created from the
context's `ObjectMapper` if there is one, and from `JacksonJsonMapper`'s own default `ObjectMapper`
otherwise:

```java
@Bean
@ConditionalOnClass(JacksonJsonMapper.class)
@ConditionalOnMissingBean(JsonMapper.class)
JsonMapper jsonMapper(ObjectProvider<ObjectMapper> mapper) {
  return mapper.getIfAvailable() == null ? new JacksonJsonMapper() : new JacksonJsonMapper(mapper.getObject());
}
```

Using the `ObjectProvider` instead of `@ConditionalOnBean(ObjectMapper.class)` avoids ordering
`bikesched-spring-boot-starter` after `JacksonAutoConfiguration` (which moved to `spring-boot-jackson` in Boot 4)
and behaves the same when the application defines no `ObjectMapper`. A user-defined `JsonMapper` bean
or a customizer wins. `tools.jackson.databind.ObjectMapper` is Jackson 3, which is what Boot 4 uses
and what our Jackson module already depends on. Both optional modules are only touched from
`@ConditionalOnClass`-guarded methods.

### 7.4 Listeners and metrics

The builder takes one `JobListener`, so the module composes all `JobListener` beans into one
`CompositeJobListener`, ordered by `Ordered`. If there is exactly one listener bean, it is used as-is.

`bikesched-micrometer` is supported from the start (decided): with the module on the classpath and a
`MeterRegistry` bean in the context, the auto-configuration creates a `MicrometerJobListener` from
that registry, tagged with the *node name*:

```java
@Bean
@ConditionalOnClass(MicrometerJobListener.class)
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnMissingBean(MicrometerJobListener.class)
@ConditionalOnProperty(name = "bikesched.metrics.enabled", matchIfMissing = true)
MicrometerJobListener micrometerJobListener(MeterRegistry registry, BikeschedProperties properties) {
  return new MicrometerJobListener(registry, Tags.of("node", properties.nodeName()));
}
```

The `node` tag is added on purpose: the meters include gauges, and two nodes sharing one registry
overwrite each other's gauge unless their tags differ (§7.4 of `metrics.md` is the manual advice this
automates). Common tags (`management.metrics.tags.*`) and the registry's other customizations apply as
usual — Boot adds them to the registry, not to the listener, so nothing is duplicated here. A
user-defined `MicrometerJobListener` bean wins, and `bikesched.metrics.enabled=false` turns the
automatic one off (for applications that want the listener with their own tags, or none at all).
Without a `MeterRegistry` (no `micrometer-core`, or metrics not registered) nothing happens.

## 8. Transactions

- **Handlers**: `@Transactional` on a `@JobHandler` method works, because the discovery invokes the
  method through the proxy instance (§5.6). Handlers run on bikesched's virtual job threads, so
  transactions are autonomous: nothing is inherited from a request thread, and there is no
  transaction context to join. `@Transactional(propagation = REQUIRED)` therefore behaves as
  `REQUIRES_NEW`, which is what a job wants; the difference matters for retries (a rolled-back
  handler means the outcome recording happens outside the transaction).
- **The module warns about exactly that** (decided). When a discovered handler method — or its class,
  including composed annotations and `@Transactional` meta-annotated ones, found via Spring's
  `MergedAnnotations`/`AnnotationUtils` — is annotated `@Transactional` with `propagation = REQUIRED`
  (the default), the registrar logs a warning once per method at startup:

  > bikesched: handler method MailJobs.sendMail is @Transactional with the default propagation
  > REQUIRED; job handlers run without an ambient transaction, so this starts a new transaction —
  > equivalent to REQUIRES_NEW. Make it explicit (`@Transactional(propagation = REQUIRES_NEW)`) to
  > document the intent.

  Methods with an explicit other propagation (`REQUIRES_NEW`, `NOT_SUPPORTED`, `SUPPORTS`, …) are
  silent: `REQUIRES_NEW` is the recommended form, and `NOT_SUPPORTED`/`SUPPORTS` are deliberate
  choices. The warning is a hint, not a failure; there is no property to silence it individually
  (logging levels are the knob).
- **Step results and job state** are written by bikesched on its own connections, outside the
  handler's transaction. Together with the compensation model this is the documented semantics
  (`steps.md`), not a Spring concern.
- **Scheduling inside a caller's transaction** (the classic "schedule the job only if my transaction
  commits"): bikesched's `connection(Connection)` builder supports running the insert on a caller's
  connection, so the Spring module offers a helper that passes the transaction-bound connection:

  ```java
  TransactionSynchronizationManager.isActualTransactionActive()
      ? bikesched.newJob(type).connection(DataSourceUtils.getConnection(dataSource))
      : bikesched.newJob(type);
  ```

  Design decision for v1: provide this as an explicit helper
  (`BikeschedTransactions.schedule(bikesched, dataSource, type)`) rather than rewriting every
  builder call; an AOP/`@Transactional`-intercepting variant is explicitly out of scope. The helper
  is documented with its caveat: `DataSourceUtils.getConnection` must be released by the surrounding
  transaction, and the insert is rolled back with it.

## 9. Diagnostics

- Startup logs, once: node name, whether polling is enabled, the number of registered handlers and
  their job types (debug), the `JsonMapper` in use, and the composed listener count.
- Errors that fail the context: duplicate job type (naming the beans, §5.7), an invalid
  `@JobHandler` signature (from `bikesched-annotations`, naming the method), an invalid property
  (`BindingResult`, standard Boot behaviour), and the `DataSource` selection failures of §4.1 through
  a `FailureAnalyzer` (registered in `META-INF/spring.factories`), so the message reads as
  "bikesched found 2 DataSource beans (a, b) and none is @Primary — set bikesched.data-source
  to pick one" instead of a stack trace. The duplicate-handler and invalid-signature failures get the
  same treatment, naming the beans and the method. A startup migration failure (§6) surfaces with
  the database URL and the failed migration, and the transaction warning of §8 is logged at `WARN`
  once per handler method.
- Debug-level "skipped a bean created after the node was closed" log (§5.2).
- Docs page `docs/spring.md` (developer docs, not this spec): quick start, the property table, the
  handler rules with a proxy note, transactions, and the "one node per application/JVM name" caveat.

## 10. Risks and sharp edges

| Risk | Handling |
|---|---|
| BPP ordering: our BPP must run before beans are used but must not depend on `Bikesched` | collect only; register from the factory method and on later bean creation (§5.2) |
| A bean is proxied before our BPP sees it (Spring's auto-proxy creator runs at high precedence, ours at the default lowest) | the BPP runs `postProcessAfterInitialization`, so it sees the finished proxy: `AopProxyUtils.ultimateTargetClass` for scanning, `bindTo` for invoking through it; a test covers both JDK and CGLIB proxies |
| `@Lazy`/prototype beans whose handlers never register | register on creation (§5.2); a job of such a type simply waits until the bean exists; log at debug |
| Two contexts in one JVM (tests) share a node name | default name from `spring.application.name` per context; tests set `bikesched.node-name` |
| Two `DataSource` beans, none selected | `bikesched.data-source` (§4.1); without it the context fails with the `FailureAnalyzer` message, never with a silently chosen database |
| Raising the poll interval to 5 seconds delays a freshly scheduled job by up to 5 seconds | documented in `docs/configuration.md` and `jobs.md` ("Scheduling"); a node that needs lower latency lowers the interval |
| Application shuts down while jobs run | `close()` releases running jobs and waits for the job threads; `Bikesched` is destroyed before the `DataSource` thanks to the dependency |
| A `@JobHandler` method on a bean that is also a `JobListener` or defines `@Bean` methods | no special handling; the type registry is the only contract |
| Users expect `@Scheduled`-like cron | explicitly out of scope, documented in §2 and the docs page |

## 11. Testing plan

1. **Conditions** (`ApplicationContextRunner`, no database; the test registers its own `DataSource`
   bean, so `spring-boot-starter-jdbc`/`spring-jdbc` is not needed on the test classpath): no
   `DataSource` → no `Bikesched` bean;
   `DataSource` present → `Bikesched` bean with `isPolling() == false` before refresh, polling after;
   `bikesched.polling.enabled=false` → never polls; a user-defined `Bikesched` bean wins; a
   `BikeschedCustomizer` is applied; `bikesched.*` properties bind (relaxed names, `Duration`
   suffixes, nested `retry-backoff`, `poll-interval`); several `DataSource` candidates without
   `bikesched.data-source` → the context fails with the §4.1 message; with the property set → that
   `DataSource` is used (asserted through the node's database, e.g. the tables appear there and not in
   the other one); `@Primary` among several → used without the property; `bikesched.data-source`
   naming a missing or non-`DataSource` bean → fails naming it;
   `node-timeout` ≤ `heartbeat-interval` → the context fails with the validation message.
2. **Handler discovery**, real PostgreSQL (Testcontainers, as in the other modules):
   - `@JobHandler` bean handles a scheduled job end to end;
   - the three signatures (`Job`, payload, both, none) and a `JobTypeHandler` bean;
   - a `@Transactional` bean proxied with `proxyTargetClass=false` (JDK proxy) **and** `true`
     (CGLIB): the handler is found and runs, and a transaction rollback in the handler is visible
     (e.g. a step write outside the transaction still happened);
   - duplicate job types across two beans fail the context with a message naming both beans;
   - an invalid signature fails the context naming the method;
   - a `@Lazy` bean created after startup registers its handler, and a job of that type runs.
3. **Lifecycle**: context close → no `RUNNING` rows for the node; a job running at shutdown is
   released and run by a second node; `startPolling()` idempotence; the node's heartbeat stops;
   `migrate-on-startup=true` fails the context against a database without the tables (or with a
   modified `V001`) and `=false` does not, while the first scheduled job migrates lazily.
4. **Listener composition and metrics**: two `JobListener` beans both receive calls; with
   `bikesched-micrometer` and a `MeterRegistry` bean, the automatic `MicrometerJobListener` records
   into the registry with the `node` tag, `bikesched.metrics.enabled=false` leaves the registry empty,
   a user-defined `MicrometerJobListener` bean wins, and no `MeterRegistry` bean means no listener.
5. **JSON**: with `bikesched-jackson` present, a record payload maps; without it, a JSON-string
   payload still works; a user `JsonMapper` bean wins.
6. **Transaction warning**: a handler method annotated `@Transactional` (default propagation), a
   class-level one, and a composed annotation each log the warning once; an explicit
   `REQUIRES_NEW`/`NOT_SUPPORTED` stays silent.
7. **No Spring on the classpath of core**: a test (or the existing ArchUnit rules) asserts
   `bikesched-core` and `bikesched-annotations` have no Spring types, and the architecture test gets
   the new module's package rules.
8. **End-to-end example** (§12.3): the `bikesched-examples` Spring Boot application, whose
   Testcontainers test covers auto-configuration, `@JobHandler` discovery and shutdown in one run in
   CI.

## 12. Implementation steps

### 12.1 Prerequisite changes in other modules (before the Spring module)

1. `bikesched-core`: make `pollInterval`, `heartbeatInterval` and `nodeTimeout` public builder options,
   raise `DEFAULT_POLL_INTERVAL` to 5 seconds, and validate `nodeTimeout > heartbeatInterval`
   (§7.1); tests for the validation and for the public options.
2. `bikesched-kotlin`: the three DSL properties for parity with the Java builder.
3. Docs in the same change: `docs/configuration.md` (three builder rows, the new poll-interval
   default, and a sentence in the scheduling section about the resulting worst-case start delay).
4. `bikesched-annotations`: instance-independent scanning and binding for proxies
   (`JobHandlers.scan(Class)`, `AnnotatedJobHandler.bindTo(Object)`, `registerOn(Bikesched, String)`,
   §5.6) with tests.
5. `bikesched-core`: a migration-only entry point, `Bikesched.migrate()`, so an application can
   validate/upgrade the schema without starting to poll (needed by the fail-fast startup of §6);
   tests for a successful migration, a schema mismatch and an unreachable database.

Items 1–3 are independent of Spring and can land first, on their own; item 4 is independent too and
only needed by §5.2.

### 12.2 The Spring module

1. `bikesched-spring-boot-starter` skeleton: build files, version catalog entries, `module-info.java`,
   `AutoConfiguration.imports`, empty auto-configuration + `ApplicationContextRunner` conditions test.
2. Properties (`BikeschedProperties`, including `data-source`) + `DataSourceSelector` +
   customizers + `Bikesched` bean + lifecycle + the `FailureAnalyzer` + tests.
3. Discovery: `JobHandlerBeanPostProcessor`, `JobHandlerBeans`, `JobTypeHandler` beans, duplicate
   diagnostics + tests (proxies, lazy beans, duplicate types).
4. `JsonMapper`, `JobListener` composition and the automatic `MicrometerJobListener` + tests.
5. Transaction helper + the `@Transactional`-without-`REQUIRES_NEW` warning + tests.
6. Docs (`docs/spring.md`, `docs/index.md`, module tables in `README.md` and `AGENTS.md`,
   `IDEAS.md` entry removed, ADR), then CI (the module publishes like the others; no workflow change
   expected).
7. Snapshot verification by consuming the snapshot from a scratch Spring Boot app (manual, once),
   including the two-`DataSource` case and a `@Transactional` handler. The example of §12.3 is the
   permanent, CI-run version of this.

### 12.3 Example application in `bikesched-examples`

A Spring Boot application in the examples module, package `org.bikesched.examples.spring`, following
the conventions of the other examples (main class + `run…` Gradle task + a section in the examples
`README.md` + a Testcontainers test):

```java
// SpringExampleApplication.java
@SpringBootApplication
public class SpringExampleApplication {
  public static void main(String[] args) { SpringApplication.run(SpringExampleApplication.class, args); }
}

// MailJobs.java — the only handler of the example; the starter finds and registers it.
@Component
public class MailJobs {
  record Mail(String to, String subject) {}

  @JobHandler(type = "send-mail")
  public void sendMail(Job job, Mail mail) {
    System.out.println("Job " + job.id() + ": sending '" + mail.subject() + "' to " + mail.to());
  }
}
```

- **No `@EnableBikesched`**: the starter's auto-configuration is the whole wiring.
- **DataSource**: the same compose database as every other example, expressed the Spring way in
  `src/main/resources/application.properties`:

  ```properties
  spring.datasource.url=jdbc:postgresql://localhost:${POSTGRES_PORT:5432}/bikesched
  spring.datasource.username=bikesched
  spring.datasource.password=bikesched
  bikesched.node-name=spring-example
  bikesched.poll-interval=1s
  ```

  `POSTGRES_PORT` is the convention `DatabaseUtil` and `compose.yaml` already use, so `runSpring`
  runs against the same `docker compose up -d` database. The `bikesched.*` lines show a property the
  starter adds (§7.1) without the module owning a `DataSource` of its own.
- **An `ApplicationRunner`** schedules one `send-mail` job, waits for it to end (Awaitility, as the
  other examples do), prints the state and closes the context, so the command terminates like the
  others instead of hanging on a non-web Boot application.
- **Build**: `implementation(project(":bikesched-spring-boot-starter"))`, plus `spring-boot-starter` and
  `spring-boot-starter-jdbc` (the example needs a pooled `DataSource`; `bikesched-spring-boot-starter` itself does
  not), a `runSpring` `JavaExec` task like the other `run…` tasks, and a row in the examples README.
- **Test** `SpringExampleTest`: starts the context against the shared Testcontainers database
  (`org.bikesched.examples.util.Database`) through `@DynamicPropertySource` (or
  `SpringApplicationBuilder.properties(…)`), so CI does not depend on `POSTGRES_PORT` or a running
  compose database, and asserts that the annotated method ran and the job ended `SUCCEEDED`.
  Deliberately plain `SpringApplication` + JUnit rather than `@SpringBootTest`: the latter would pull
  `spring-boot-starter-test` (Mockito, JSONassert, …) into the examples module for one assertion.

Because this example runs in CI on every build, it is the permanent end-to-end proof of the starter
(auto-configuration, handler discovery, lifecycle, shutdown) — stronger than the manual snapshot check
of §12.2 step 7, which stays for catching publishing/consumption mistakes.

## 13. Decisions and remaining questions

### 13.1 Decided

| Question | Decision |
|---|---|
| Public builder options | `pollInterval`, `heartbeatInterval` and `nodeTimeout` become public; poll default 1s → 5s (§7.1) |
| Module layout | one module, artifact `bikesched-spring-boot-starter`, package + JPMS module `org.bikesched.spring`; a later `-autoconfigure` split stays transparent (§3) |
| Handler discovery | Option A: `BeanPostProcessor` collecting candidates + `JobHandlerBeans` holder registering before polling (§5.5) |
| Schema migration | fail-fast: `Bikesched.migrate()` in core, `bikesched.migrate-on-startup=true` by default (§6) |
| Micrometer | supported from v1: automatic `MicrometerJobListener` from a `MeterRegistry` bean, tagged with the node name, `bikesched.metrics.enabled` to turn it off (§7.4) |
| `RetryBackoff` properties | nested properties (`fixed`, `exponential.initial`/`exponential.max`) as sketched in §7.1 |
| Transaction semantics | document `REQUIRED == REQUIRES_NEW` for handlers *and* warn once per handler method that uses the default propagation (§8) |
| `DataSource` selection | `bikesched.data-source` names the bean; unique/`@Primary` otherwise (§4.1) |

### 13.2 Still open

1. **`migrate-on-startup` when `bikesched.polling.enabled=false`**: fail-fast is the default because a
   scheduling-only application also wants its schema validated, but it means such an application no
   longer starts while its database is unreachable — the case `autoStartPolling(false)` was designed
   for. Keep `true` for both, or default to `true` only when polling is enabled?
2. **Limits of the transaction warning**: it only sees annotations. A handler that manages its
   transaction itself (`TransactionTemplate`, a repository call) gets no hint, and a handler whose
   class is annotated `@Transactional` with an explicit `REQUIRES_NEW` is silent for every method.
   Acceptable as a documented limitation, or worth a line in `docs/spring.md` about how to write
   transactional handlers?

## 14. Alternatives considered

- **Spring beans as handlers via an interface** (`class X implements JobTypeHandler`), no annotation
  scanning: less magic, but the annotation is already the project's mechanism and reads better for
  DI-heavy code (`@JobHandler(type = "send-invoice")` next to `@Transactional`).
- **`BeanFactoryPostProcessor`/classpath scanning of `@JobHandler` types** (Spring's
  `ClassPathScanningCandidateComponentProvider`): finds types that were never registered as beans, and
  would register handlers for beans the application deliberately did not create; also needs the
  package list. Rejected, and the naming collision with `@Component`-scanned classes is a real hazard.
- **Registering handlers in `ContextRefreshedEvent`** (§5.4): a polling window with missing handlers.
- **Full Spring integration in core** (`spring-context` as an optional dependency): violates the "core
  stays free of concrete libraries" rule in `AGENTS.md`.