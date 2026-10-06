# bikesched examples

Runnable examples of [bikesched](../README.md), one package per scenario. Each example has a `main`
that runs against the PostgreSQL from the repository's `compose.yaml`, and a test that runs against
PostgreSQL 18 with Testcontainers: one container shared by all example tests, provided by the
test class `util.Database`. Payloads, step results and events are records, mapped to JSON
by [`bikesched-jackson`](../docs/configuration.md#jackson).

## Running

```shell
docker compose up -d                      # from the repository root
./gradlew :bikesched-examples:runSimple   # one handler, one job; honors POSTGRES_PORT, like docker compose
./gradlew :bikesched-examples:runEvent    # two jobs waiting for an event, published right away and 3s later
./gradlew :bikesched-examples:run         # taxi booking
./gradlew :bikesched-examples:runCinema   # cinema tickets
./gradlew :bikesched-examples:test        # the example tests, against Testcontainers
```

## Simple

Package [`org.bikesched.examples.simple`](src/main/java/org/bikesched/examples/simple): the
smallest setup, one job handler and one job with a `JobPayload` record (a `UUID`):

```java
JobType<JobPayload> JOB_TYPE = JobType.of("simple-job", JobPayload.class);

bikesched.registerJobHandler(JOB_TYPE, (job, payload) -> System.out.println("Job " + job.id() + ": " + payload));
bikesched.newJob(JOB_TYPE).payload(new JobPayload(UUID.randomUUID())).schedule();
```

`SimpleExampleTest` runs the job and waits until it ended.

## Event

Package [`org.bikesched.examples.event`](src/main/java/org/bikesched/examples/event): one job with
two steps. The first sends the payload's id to `EventService`, which returns an id of its own and
publishes an event keyed by it, with both ids as payload (`EventPayload(id, jobId)`); the second
waits for that event:

```java
UUID id = job.step("call-service", UUID.class, _ -> service.process(payload.id(), payload.delay()));
EventPayload event = job.waitForEvent("await-event", EVENT_TYPE, id.toString()).receive();
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

## Taxi booking

Package [`org.bikesched.examples.taxi`](src/main/java/org/bikesched/examples/taxi): booking a taxi
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

Package [`org.bikesched.examples.cinema`](src/main/java/org/bikesched/examples/cinema): ordering
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
