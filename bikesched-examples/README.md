# bikesched examples

Runnable examples of [bikesched](../README.md), one package per scenario, in three Gradle modules:

| Module | Examples | Dependencies |
|---|---|---|
| [`java`](java) (`:bikesched-examples:java`) | [simple](#simple), [event](#event), [publish](#publish), [outcome](#outcome), [poll](#poll), [rate limit](#rate-limit), [chat](#chat), [approval](#approval), [fan-out](#fan-out), [recurring](#recurring), [annotation](#annotation), [taxi](#taxi-booking), [cinema](#cinema-tickets), [admin](#admin) | `bikesched-jackson`, `bikesched-annotations`, `bikesched-admin`, Bucket4j |
| [`kotlin`](kotlin) (`:bikesched-examples:kotlin`) | [Shop](#kotlin-shop) | `bikesched-kotlin`, Ktor, Exposed, kotlinx-serialization |
| [`spring`](spring) (`:bikesched-examples:spring`) | [Spring Boot](#spring-boot) | `bikesched-spring-boot-starter`, `bikesched-jackson`, Spring Data JPA |

Each example has a `main` that runs against the PostgreSQL from the repository's `compose.yaml`,
and a test that runs against PostgreSQL 18 with Testcontainers: in the `java` module, one container
shared by all example tests, provided by the test class `util.Database`. Payloads, step results
and events are records, mapped to JSON by
[`bikesched-jackson`](../docs/getting-started/configuration.md#jackson).

## Running

```shell
docker compose up -d                      # from the repository root
./gradlew :bikesched-examples:java:runSimple      # one handler, one job; honors POSTGRES_PORT, like docker compose
./gradlew :bikesched-examples:java:runAnnotation  # job handlers as @JobHandler methods
./gradlew :bikesched-examples:java:runEvent       # two jobs waiting for an event, published right away and 3s later
./gradlew :bikesched-examples:java:runPublish     # a job publishing an event exactly once, another job waiting for it
./gradlew :bikesched-examples:java:runOutcome     # declined cards as step results, an outage retried
./gradlew :bikesched-examples:java:runPoll        # a webhook with a polling fallback
./gradlew :bikesched-examples:java:runRateLimit   # a provider's rate limit on two nodes, with Bucket4j
./gradlew :bikesched-examples:java:runChat        # chat messages delivered twice, answered once
./gradlew :bikesched-examples:java:runApproval    # an expense approval with a reminder and an escalation
./gradlew :bikesched-examples:java:runFanOut      # one child job per photo, the parent waits for all
./gradlew :bikesched-examples:java:runRecurring   # a recurring job on two nodes, each run happens once
./gradlew :bikesched-examples:java:run            # taxi booking
./gradlew :bikesched-examples:java:runCinema      # cinema tickets
./gradlew :bikesched-examples:java:runAdmin       # the admin UI next to demo jobs: http://localhost:8081/bikesched/
./gradlew :bikesched-examples:spring:bootRun      # Spring Boot application wired by the starter
./gradlew :bikesched-examples:kotlin:runShop      # Kotlin shop with Ktor, Exposed, and kotlinx-serialization
./gradlew :bikesched-examples:java:test :bikesched-examples:spring:test  # the example tests, against Testcontainers
```

## Simple

Package [`org.bikesched.examples.simple`](java/src/main/java/org/bikesched/examples/simple): the
smallest setup, one job handler and one job with a `JobPayload` record (a `UUID`):

```java
JobType<JobPayload> JOB_TYPE = JobType.of("simple-job", JobPayload.class);

bikesched.registerJobHandler(JOB_TYPE, (job, payload) -> System.out.println("Job " + job.id() + ": " + payload));
bikesched.newJob(JOB_TYPE).payload(new JobPayload(UUID.randomUUID())).schedule();
```

`SimpleExampleTest` runs the job and waits until it ended.

## Event

Package [`org.bikesched.examples.event`](java/src/main/java/org/bikesched/examples/event): one job with
two steps. The first sends the payload's id to `EventService`, which returns an id of its own and
publishes an event keyed by it, with both ids as payload (`EventPayload(id, jobId)`); the second
waits for that event:

```java
UUID id = job.step("call-service", UUID.class, _ -> service.process(payload.id(), payload.delay()));
EventPayload event = job.awaitEvent("await-event", EVENT_TYPE, id.toString());
```

The returned id is the step's stored result, so a later run of the handler waits for the same
event.

`scheduleJob(delay)` decides per job when the service publishes the event, and `main` schedules
one of each:

```java
example.scheduleJob(null);                    // published right away: the wait receives it at once
example.scheduleJob(Duration.ofSeconds(3));   // published 3 seconds later on another thread
```

With a delay, the job is suspended at the wait (`WAITING`, without a thread) and resumed by the
event: its handler runs again from the start, and step 1 returns the stored id without calling the
service again. Without one, the event is published before the job starts waiting, so the wait
receives it right away (see [Events](../docs/multi-step-jobs/events.md)).

`EventExampleTest` runs both variants and checks that the delayed job waits and then succeeds.

## Publish

Package [`org.bikesched.examples.publish`](java/src/main/java/org/bikesched/examples/publish): a job
publishes an event as a durable step, another job waits for it. Placing an order schedules two
jobs: `notify-customer` waits for `order-shipped` keyed by the order id, and `ship-order` hands the
parcel over to the carrier and publishes the event:

```java
String trackingId = job.step("hand-over-to-carrier", String.class, _ -> carrier.handOver(order));
UUID eventId = job.newEvent(ORDER_SHIPPED, order.orderId())
        .payload(new OrderShipped(order.orderId(), trackingId))
        .publish();
job.sleep("await-pickup", Duration.ofSeconds(1));
```

The event and the completed publish step commit in one transaction. After the sleep, the handler
runs again from the start: the tracking id comes from the stored step result, and `publish()`
finds its step and returns the same event id without publishing again (see
[Publishing from jobs](../docs/multi-step-jobs/events.md#publishing-from-jobs)).

`PublishExampleTest` checks that both jobs succeed, that every run of the shipping handler got the
same event id, and that `bikesched_events` holds the event once.

## Outcome

Package [`org.bikesched.examples.outcome`](java/src/main/java/org/bikesched/examples/outcome): business
outcomes as step results. Charging a card can end in a decline, which a retry would not change, so
the step returns it as a result instead of throwing. `ChargeOutcome` is a sealed interface with the
success and the expected error; the Jackson annotations keep the subtype in the stored JSON:

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
@JsonSubTypes({@JsonSubTypes.Type(value = Charged.class, name = "charged"),
               @JsonSubTypes.Type(value = Declined.class, name = "declined")})
public sealed interface ChargeOutcome {
    record Charged(String receiptId) implements ChargeOutcome {}
    record Declined(String code, String reason) implements ChargeOutcome {}
}

ChargeOutcome outcome = job.step("charge-card", ChargeOutcome.class, step -> {
    try {
        return new Charged(provider.charge(…, step.jobId() + ":" + step.name()));
    } catch (CardDeclinedException e) {
        return new Declined(e.code(), e.getMessage());   // stored, not retried
    }
});
switch (outcome) {
    case Charged charged -> …    // mail the receipt
    case Declined declined -> …  // mail the customer, then job.cancel("payment declined: " + code)
}
```

`SimulatedPaymentProvider` has test cards like real providers: one is charged, two are declined
(`card_declined`, `insufficient_funds`), and one meets an outage (`IOException`) on its first call.
The outage fails the step, so the job is retried and the card charged on the second call; a decline
is not retried.

**Concepts shown:** [business outcomes as
results](../docs/multi-step-jobs/steps.md#business-outcomes-as-results), technical errors left to
[retries](../docs/jobs/README.md#failures-and-retries), and [idempotency
keys](../docs/multi-step-jobs/steps.md#idempotency-keys) for the provider.

`OutcomeExample` charges three orders (charged, declined, outage) and prints what happens;
`OutcomeExampleTest` checks each card's job state and how often the provider was called.

## Poll

Package [`org.bikesched.examples.poll`](java/src/main/java/org/bikesched/examples/poll): a webhook with
a polling fallback. `SimulatedPaymentProvider` settles a payment some time after it was created and
sends a webhook, which the application's endpoint publishes as the event `payment-settled`, keyed
by the payment id, with the webhook's delivery id as [idempotency
key](../docs/multi-step-jobs/events.md#idempotency-keys), so a delivery the provider repeats is
published once:

```java
bikesched.newEvent(PAYMENT_SETTLED, delivery.payment().paymentId())
        .payload(delivery.payment())
        .idempotencyKey(delivery.deliveryId())
        .publish();   // a repeated delivery returns the event published first
```

The job waits for the webhook for two seconds and polls the provider's status every second if it
doesn't arrive:

```java
String paymentId = job.step("create-payment", String.class, step -> provider.createPayment(…));
PaymentSettled webhook = job.waitForEvent("await-webhook", PAYMENT_SETTLED, paymentId)
        .timeout(WEBHOOK_TIMEOUT)
        .onFailure(failing -> failing.resolve(null))   // no webhook in time: poll instead
        .receive();
PaymentSettled settled = webhook != null
        ? webhook
        : job.newPoll("poll-payment", PaymentSettled.class, POLL_INTERVAL)
                .timeout(Duration.ofSeconds(30))
                .run(_ -> provider.status(paymentId).map(Poll::ready).orElseGet(Poll::notReady));
job.step("mark-paid", () -> { … });
```

`pay(orderId, settlesAfter, webhook)` decides per job when the payment settles and whether its
webhook arrives, and `main` takes one payment of each:

```java
example.pay("order-webhook", Duration.ofSeconds(1), Webhook.DELIVERED);              // the webhook arrives in time
example.pay("order-webhook-twice", Duration.ofSeconds(1), Webhook.DELIVERED_TWICE);  // published once
example.pay("order-polled", Duration.ofSeconds(4), Webhook.LOST);                    // polled until settled
```

The last job's wait times out after two seconds; then every check that finds the payment still
pending suspends the job until the next one, as one step (`poll-payment`) for all checks. See
[Polling](../docs/multi-step-jobs/steps.md#polling).

`PollExampleTest` checks that the delivered webhook needs no status call, that a webhook delivered
twice returns the same event both times and stores one row in `bikesched_events`, and that for
the lost one the number of status calls equals the poll step's checks, with a single history entry
(`ready after n checks`) for the poll.

## Rate limit

Package [`org.bikesched.examples.ratelimit`](java/src/main/java/org/bikesched/examples/ratelimit):
a provider's rate limit with [Bucket4j](https://github.com/bucket4j/bucket4j) and
[`job.postpone`](../docs/jobs/README.md#postponing). `SimulatedSmsProvider` accepts at most two
messages within any second and rejects (and prints) every call over that. The job `send-sms` takes a
token from a bucket before each call; without one, it postpones the job until the bucket has one
again:

```java
job.step("send", String.class, _ -> {
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (!probe.isConsumed()) {
        job.postpone(Duration.ofNanos(probe.getNanosToWaitForRefill()));   // no attempt used up
    }
    return provider.send(sms.to(), sms.text());
});
```

The bucket allows one message every 600 ms, a little under the provider's limit, and lives in
PostgreSQL (`bucket4j_jdk17-postgresql`, table `ratelimit_buckets`, created by the example), so
the limit holds across nodes: `main` starts two nodes and schedules ten messages at once, and the
output shows postponed steps but no rejected call.

`RateLimitExampleTest` runs the same on two nodes and checks that every job succeeded in its first
attempt, the provider saw ten calls and rejected none, and the history has `POSTPONED` steps.

## Chat

Package [`org.bikesched.examples.chat`](java/src/main/java/org/bikesched/examples/chat): incoming
messages deduplicated with an [idempotency key](../docs/jobs/README.md#idempotency-keys). A chat
platform delivers each message to the application's webhook at least once; the webhook schedules the
job `answer-chat-message` with the platform's message id as key, and the job replies in a step:

```java
UUID jobId = bikesched.newJob(ANSWER_MESSAGE)
        .payload(message)
        .idempotencyKey(message.messageId())   // a redelivery returns the first job's id
        .schedule();
```

`deliver(message, delivery)` decides per message how the platform delivers it, and `main` sends one
of each:

```java
example.deliver(message("alice", "hi"), Delivery.ONCE);
example.deliver(message("bob", "help"), Delivery.REDELIVERED);       // again after the job answered
example.deliver(message("carol", "hello?"), Delivery.CONCURRENTLY);  // twice at the same time
```

Message ids are new on every run: a key stays taken while its job exists (the
[job retention](../docs/jobs/README.md#job-retention)).

`ChatExampleTest` runs each delivery and checks that all deliveries returned the same job id, the
message got one reply, and `bikesched_jobs` holds one job with the key.

## Approval

Package [`org.bikesched.examples.approval`](java/src/main/java/org/bikesched/examples/approval): an
expense approval with a reminder and an escalation. The job `approve-expense` asks the manager and
waits for the event `expense-decided`, keyed by the expense id. Each wait has a
[timeout](../docs/multi-step-jobs/events.md#timeouts) whose failure handler resolves it with `null`,
so the job continues with the next stage instead of failing:

1. `request-approval`, `await-decision` (2 seconds here, days in practice)
2. without a decision: `send-reminder`, `await-decision-after-reminder` (2 seconds)
3. still without one: `escalate` to the finance lead, `await-escalated-decision` (no timeout)
4. `pay-out` or `notify-rejection`

```java
Decision decision = job.waitForEvent("await-decision", EXPENSE_DECIDED, id)
        .timeout(REMIND_AFTER)
        .onFailure(failing -> failing.resolve(null))   // no decision in time
        .receive();
if (decision == null) {
    job.step("send-reminder", () -> inbox.remind(expense.manager(), expense));
    decision = job.waitForEvent("await-decision-after-reminder", EXPENSE_DECIDED, id)…
}
```

`decide(expense, approver, approved)` is the approval UI: it publishes the event. `main` plays the
approvers, each after the inbox received the message they react to:

| Expense | Approver | Outcome |
|---|---|---|
| taxi | the manager approves right away | paid out |
| dinner | the manager rejects after the reminder | not paid |
| laptop | the manager doesn't answer, the finance lead approves after the escalation | paid out |

`ApprovalExampleTest` covers the three cases and checks the messages each approver received.

## Fan-out

Package [`org.bikesched.examples.fanout`](java/src/main/java/org/bikesched/examples/fanout):
fan-out/fan-in with [child jobs](../docs/multi-step-jobs/child-jobs.md). The job `import-album`
starts one child job `resize-photo` per uploaded file, then waits for each child and publishes the
album:

```java
List<UUID> children = album.files().stream()
        .map(file -> job.newJob(RESIZE_PHOTO).payload(new Photo(albumId, file)).stepName("resize " + file).schedule())
        .toList();
for (int i = 0; i < children.size(); i++) {
    switch (job.waitForJob(children.get(i), Thumbnail.class)) {
        case JobResult.Succeeded<Thumbnail>(_, Thumbnail thumbnail) -> thumbnails.add(thumbnail);
        case JobResult.Failed<Thumbnail> _ -> skipped.add(album.files().get(i));
        case JobResult<Thumbnail> other -> throw new IllegalStateException("Unexpected outcome: " + other);
    }
}
```

A child sets the thumbnail as its result (`job.complete`); a file that is not an image fails it
without retries. The parent receives the failure as an outcome and skips the file. Resizing takes
a second per photo, and the children run in parallel, so the album of four files is done after
about a second. The parent's result (`ImportedAlbum`) lists the thumbnails and the skipped files.

`FanOutExampleTest` checks the parent's result, that each file was resized once by its own child
(`parent_job_id`), and that the children ran in parallel.

## Recurring

Package [`org.bikesched.examples.recurring`](java/src/main/java/org/bikesched/examples/recurring):
periodic work with a [recurring job](../docs/jobs/recurring-jobs.md). `main` starts two nodes, and
both register the recurring job `clean-up-sessions`, as every node does on startup:

```java
bikesched.newJob(CLEAN_UP_SESSIONS)
        .handler(_ -> out.printf("%s [%s] run %d: removed expired sessions%n", timestamp(), node, runs.incrementAndGet()))
        .schedule(Recurrence.every(Duration.ofSeconds(1)));
```

Registering is idempotent and bikesched keeps one occurrence pending, so the output shows one run
per second, on either node, never two at once. After five runs `main` stops; the pending
occurrence stays, and the next start continues with it.

`RecurringExampleTest` runs it on two nodes and checks that the runs are at least 800 ms apart and
one occurrence is pending, then deletes it with `removeRecurringJob`.

## Annotation

Package [`org.bikesched.examples.annotation`](java/src/main/java/org/bikesched/examples/annotation): job
handlers declared as methods with `@JobHandler` (module `bikesched-annotations`). Each method takes
the `Job`, the payload (here a `Mail` record), both or neither:

```java
public final class MailJobs {
    @JobHandler(type = "send-mail")
    public void sendMail(Job job, Mail mail) { … }

    @JobHandler(type = "archive-mail")
    public void archiveMail(Mail mail) { … }

    @JobHandler(type = "check-mailbox")
    public void checkMailbox() { … }
}

JobHandlers.register(bikesched, new MailJobs());
```

`AnnotationExample` schedules one job of each type; `AnnotationExampleTest` checks that they
succeed.

## Taxi booking

Package [`org.bikesched.examples.taxi`](java/src/main/java/org/bikesched/examples/taxi): booking a taxi
ride as one job (`taxi-ride`, see `TaxiBooking`) that talks to a taxi broker, a payment service and
push notifications (simulated in `Simulated*`). The job and its two events are declared as typed
constants (`JobType<RideRequest>`, `EventType<DriverOffer>`, `EventType<PriceDecision>`), so
payloads need no casts or `Class` arguments.

**The happy path:**

1. `send-to-broker`: the ride request goes to the broker (compensation: withdraw it).
2. `await-driver`: waits for the event `taxi-driver-accepted`, keyed by the ride id, with the
   driver's price.
3. `ask-for-price`: the customer is asked to accept the price.
4. `await-price-decision`: waits for the event `taxi-price-decided`.
5. `reserve-payment`: reserves the price (compensation: release the reservation).
6. `approve-ride`: approves the ride at the broker.
7. `notify-eta`: sends the customer a push notification with the estimated time of arrival.

**When things go wrong:**

| Situation | Outcome |
|---|---|
| no driver accepts in time | the customer is notified, the job is cancelled and the request withdrawn |
| the customer declines the price, or doesn't answer in time | the job is cancelled and the request withdrawn |
| the customer cancels the ride (`Bikesched.cancel`) | the job is cancelled; the request is withdrawn and a payment reservation released |
| the broker cannot approve the ride | after its retries the job fails; the payment is released and the request withdrawn |

**Concepts shown:** [durable steps](../docs/multi-step-jobs/steps.md#durable-steps),
[compensation](../docs/multi-step-jobs/steps.md#compensation),
[cancellation](../docs/jobs/README.md#cancellation) from inside and outside the handler, and [event
waits with timeouts](../docs/multi-step-jobs/events.md#timeouts) resolved with a fallback by their
failure handler. The waits suspend the job without holding a thread; publishing an event resumes it.

`TaxiBookingExample` plays three rides (booked, cancelled by the customer, failed approval) and
prints what happens; `TaxiBookingTest` covers the booked ride, a declined price, no driver, a
cancellation by the customer and a failed approval.

## Cinema tickets

Package [`org.bikesched.examples.cinema`](java/src/main/java/org/bikesched/examples/cinema): ordering
cinema tickets as one job (`cinema-ticket-order`) with a handler class that carries its job type
(`JobTypeHandler`). The cinema, the payment provider and the mail server are simulated with
console output.

```java
public final class TicketOrderHandler implements JobTypeHandler<TicketOrder> {
    public JobType<TicketOrder> type() { return CinemaJobs.TICKET_ORDER; }
    public void handle(Job job, TicketOrder order) throws Exception { … }
}

bikesched.registerJobHandler(new TicketOrderHandler());          // no separate type argument
bikesched.newJob(CinemaJobs.TICKET_ORDER).payload(order).schedule();
```

**Steps:**

1. `reserve-seats`: holds the seats at the cinema.
2. `charge-payment`: charges the order's total.
3. `mail-tickets`: mails the tickets to the customer.

**Concepts shown:** [typed job types](../docs/jobs/README.md#typed-job-types) with a self-describing
handler class, the job type as a shared constant (`CinemaJobs`) used by the code ordering tickets,
and [durable steps](../docs/multi-step-jobs/steps.md#durable-steps): a retry skips completed steps
and reuses their results, so seats aren't reserved or cards charged twice.

`CinemaExample` orders tickets for two customers and prints each step; `CinemaTest` checks the
steps and their output.

## Admin

Package [`org.bikesched.examples.admin`](java/src/main/java/org/bikesched/examples/admin): the
[admin UI](../docs/operations/admin.md) served by the JDK's `HttpServer` next to a few demo jobs, so
the page has something to show: invoices (every fourth one fails), orders that start a parcel as a
child job and then wait for a payment event (half of them are paid), the parcels, and
`sync-inventory`, a [recurring job](../docs/jobs/recurring-jobs.md) running every 10 seconds with
two steps and no waits.

```java
HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
server.createContext("/bikesched", BikeschedAdmin.of(bikesched).showPayloads(true).httpHandler());
server.start();
```

The node records the [job history](../docs/jobs/README.md#job-history) for seven days
(`BikeschedUtil.builder(…).jobHistory(…)`), so every job detail has a History tab; it is off by
default. The demo data holds no secrets, so the example shows payloads; they are hidden by default.
Open http://localhost:8081/bikesched/ (`ADMIN_PORT` changes the port), retry a failed invoice or
cancel a waiting order. `AdminExampleTest` serves the page on a random port and waits until the
failed invoices show up in the JSON API, with their history, and the inventory sync has run
both steps.

## Kotlin Shop

Package [`org.bikesched.examples.kotlin`](kotlin/src/main/kotlin/org/bikesched/examples/kotlin): a
Ktor REST API (orders, Exposed) around two jobs, in
[`JobHandlers.kt`](kotlin/src/main/kotlin/org/bikesched/examples/kotlin/shop/JobHandlers.kt):

- `process-order` offers payment options as a durable step.
- `process-payment` charges the order in a `charge` step with its own `retryBackoff` (exponential)
  and an `onFailure` handler. A failing charge throws, so the step isn't stored as done and the
  retry charges again; once the job's attempts are used up, `onFailure` marks the order `FAILED`
  before the job fails for good — the handler runs even if the node dies right after the last
  attempt.

The order routes store the order and schedule `process-order` in one Exposed transaction
([`Routes.kt`](kotlin/src/main/kotlin/org/bikesched/examples/kotlin/shop/Routes.kt)), a
[transactional outbox](../docs/patterns.md#transactional-outbox): the job exists only if the order
does.

`MockPaymentService` fails the first order's payment twice then succeeds, and every further
order's payment always fails, so `ShopExample` shows both outcomes: the first order recovers via
retry, the second ends up `FAILED` through the `onFailure` handler.

## Spring Boot

Package [`org.bikesched.examples.spring`](spring/src/main/java/org/bikesched/examples/spring): a Spring
Boot application wired entirely by [`bikesched-spring-boot-starter`](../docs/integrations/spring.md)
— no builder code, no `BikeschedUtil`; the starter builds the node from the context's `DataSource`
and discovers the `@JobHandler` and `@JobFailureHandler` methods. The scenario is deliberately **two
microservices that share no data** (in one JVM here): `OrderService` and `FulfillmentService` talk
only through a bikesched job payload and a keyed event, each owns its own JPA entity, and
`Fulfillment` references the order by a plain `UUID` column — no foreign key, no association.

```java
@Service
public class OrderService {
  @Transactional
  public UUID placeOrder(String customer, String item) {
    Order order = orders.save(new Order(customer, item));
    return bikesched.newJob(FULFILL_ORDER).payload(new FulfillOrder(order.getId()))
        .connection(BikeschedTransactions.transactionalConnection(dataSource))   // job + order commit together
        .schedule();
  }

  @JobHandler(type = "fulfill-order")                                             // found by the starter
  public void fulfill(Job job, FulfillOrder command) throws Exception {
    Order order = orders.findById(command.orderId()).orElseThrow();
    var fulfillmentId = job.step("fulfill", UUID.class, _ -> fulfillment.fulfill(order.getId(), order.getItem()));
    job.awaitEvent("await-fulfillment", FULFILLMENT_FINISHED, fulfillmentId.toString());
    order.markFulfilled(); // …
  }

  @JobFailureHandler(type = "fulfill-order")                                      // once all attempts failed
  public void fulfillmentFailed(FailedJob failed, FulfillOrder command) {
    orders.findById(command.orderId()).orElseThrow().markFailed(); // …
  }
}
```

`FulfillmentService` stores its `Fulfillment` and publishes the `fulfillment-finished` event on the
same transaction, so the job only resumes once the fulfillment row exists. The `DataSource` is
Spring Boot's own, from the `spring.datasource.*` properties in `application.properties` (the
compose database, honoring `POSTGRES_PORT`). Items out of stock make the `fulfill` step throw;
once the job's attempts are used up, the `@JobFailureHandler` marks the order failed.

**Concepts shown:** auto-configuration and handler discovery ([Spring
Boot](../docs/integrations/spring.md)), [transactional scheduling and event
publishing](../docs/jobs/README.md#transactional-scheduling) from both "services", a [durable
step](../docs/multi-step-jobs/steps.md#durable-steps) whose result keys the [event
wait](../docs/multi-step-jobs/events.md) that suspends the job without a thread, a [job failure
handler](../docs/jobs/README.md#failure-handlers) and JPA entities coupled by UUID only.

It lives in its own module (`:bikesched-examples:spring`) with the Spring Boot Gradle plugin, so
the plain examples stay free of Spring. `SpringExampleApplication` places two orders (a bike and an
out-of-stock unicorn), waits for their jobs and closes the context, so `bootRun` terminates like the
other examples; `SpringExampleTest` runs the same application against a Testcontainers database
(passed in as `spring.datasource.*`) and checks the fulfilled and the failed order.
