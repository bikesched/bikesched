# bikesched examples

Runnable examples of [bikesched](../README.md), one package per scenario, in two Gradle modules:

| Module | Examples | Dependencies |
|---|---|---|
| [`java`](java) (`:bikesched-examples:java`) | [simple](#simple), [event](#event), [publish](#publish), [outcome](#outcome), [poll](#poll), [annotation](#annotation), [taxi](#taxi-booking), [cinema](#cinema-tickets) | `bikesched-jackson`, `bikesched-annotations` |
| [`spring`](spring) (`:bikesched-examples:spring`) | [Spring Boot](#spring-boot) | `bikesched-spring-boot-starter`, `bikesched-jackson`, Spring Data JPA |

Each example has a `main` that runs against the PostgreSQL from the repository's `compose.yaml`,
and a test that runs against PostgreSQL 18 with Testcontainers: in the `java` module, one container
shared by all example tests, provided by the test class `util.Database`. Payloads, step results
and events are records, mapped to JSON by [`bikesched-jackson`](../docs/configuration.md#jackson).

## Running

```shell
docker compose up -d                      # from the repository root
./gradlew :bikesched-examples:java:runSimple      # one handler, one job; honors POSTGRES_PORT, like docker compose
./gradlew :bikesched-examples:java:runAnnotation  # job handlers as @JobHandler methods
./gradlew :bikesched-examples:java:runEvent       # two jobs waiting for an event, published right away and 3s later
./gradlew :bikesched-examples:java:runPublish     # a job publishing an event exactly once, another job waiting for it
./gradlew :bikesched-examples:java:runOutcome     # declined cards as step results, an outage retried
./gradlew :bikesched-examples:java:runPoll        # a webhook with a polling fallback
./gradlew :bikesched-examples:java:run            # taxi booking
./gradlew :bikesched-examples:java:runCinema      # cinema tickets
./gradlew :bikesched-examples:spring:bootRun      # Spring Boot application wired by the starter
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
receives it right away (see [Events](../docs/events.md)).

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
[Publishing from jobs](../docs/events.md#publishing-from-jobs)).

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

**Concepts shown:** [business outcomes as results](../docs/steps.md#business-outcomes-as-results),
technical errors left to [retries](../docs/jobs.md#failures-and-retries), and
[idempotency keys](../docs/steps.md#idempotency-keys) for the provider.

`OutcomeExample` charges three orders (charged, declined, outage) and prints what happens;
`OutcomeExampleTest` checks each card's job state and how often the provider was called.

## Poll

Package [`org.bikesched.examples.poll`](java/src/main/java/org/bikesched/examples/poll): a webhook with
a polling fallback. `SimulatedPaymentProvider` settles a payment some time after it was created and
sends a webhook, which the application's endpoint publishes as the event `payment-settled`, keyed
by the payment id. The job waits for the webhook for two seconds and polls the provider's status
every second if it doesn't arrive:

```java
String paymentId = job.step("create-payment", String.class, step -> provider.createPayment(…));
PaymentSettled webhook = job.waitForEvent("await-webhook", PAYMENT_SETTLED, paymentId)
        .timeout(WEBHOOK_TIMEOUT, () -> null)
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
example.pay("order-webhook", Duration.ofSeconds(1), Webhook.DELIVERED);   // the webhook arrives in time
example.pay("order-polled", Duration.ofSeconds(4), Webhook.LOST);         // polled until settled
```

The second job's wait times out after two seconds; then every check that finds the payment still
pending suspends the job until the next one, as one step (`poll-payment`) for all checks. See
[Polling](../docs/steps.md#polling).

`PollExampleTest` checks that the delivered webhook needs no status call, and that for the lost
one the number of status calls equals the poll step's checks, with a single history entry
(`ready after n checks`) for the poll.

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

**Concepts shown:** [durable steps](../docs/steps.md#durable-steps),
[compensation](../docs/steps.md#compensation), [cancellation](../docs/jobs.md#cancellation) from
inside and outside the handler, and [event waits with timeouts](../docs/events.md#timeouts). The
waits suspend the job without holding a thread; publishing an event resumes it.

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

**Concepts shown:** [typed job types](../docs/jobs.md#typed-job-types) with a self-describing
handler class, the job type as a shared constant (`CinemaJobs`) used by the code ordering tickets,
and [durable steps](../docs/steps.md#durable-steps): a retry skips completed steps and reuses
their results, so seats aren't reserved or cards charged twice.

`CinemaExample` orders tickets for two customers and prints each step; `CinemaTest` checks the
steps and their output.

## Spring Boot

Package [`org.bikesched.examples.spring`](spring/src/main/java/org/bikesched/examples/spring): a Spring
Boot application wired entirely by [`bikesched-spring-boot-starter`](../docs/spring.md) — no builder
code, no `BikeschedUtil`; the starter builds the node from the context's `DataSource` and discovers
the `@JobHandler` method. The scenario is deliberately **two microservices that share no data**
(in one JVM here): `OrderService` and `FulfillmentService` talk only through a bikesched job payload
and a keyed event, each owns its own JPA entity, and `Fulfillment` references the order by a plain
`UUID` column — no foreign key, no association.

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
    var fulfillmentId = job.step("fulfill", UUID.class, _ -> fulfillment.fulfill(command.orderId()));
    job.awaitEvent("await-fulfillment", FULFILLMENT_FINISHED, fulfillmentId.toString());
    orders.findById(command.orderId()).orElseThrow().markFulfilled(); // …
  }
}
```

`FulfillmentService` stores its `Fulfillment` and publishes the `fulfillment-finished` event on the
same transaction, so the job only resumes once the fulfillment row exists. The `DataSource` is
Spring Boot's own, from the `spring.datasource.*` properties in `application.properties` (the
compose database, honoring `POSTGRES_PORT`).

**Concepts shown:** auto-configuration and handler discovery ([Spring Boot](../docs/spring.md)),
[transactional scheduling and event publishing](../docs/jobs.md#transactional-scheduling) from both
"services", a [durable step](../docs/steps.md#durable-steps) whose result keys the
[event wait](../docs/events.md) that suspends the job without a thread, and JPA entities coupled by
UUID only.

It lives in its own module (`:bikesched-examples:spring`) with the Spring Boot Gradle plugin, so
the plain examples stay free of Spring. `SpringExampleApplication` places one order, waits for its
job and closes the context, so `bootRun` terminates like the other examples; `SpringExampleTest`
runs the same application against a Testcontainers database (passed in as `spring.datasource.*`)
and checks the order and its fulfillment.
