---
title: "Patterns"
weight: 4
---

# Patterns

Common problems and how to solve them with bikesched. Each pattern links a runnable example with a
test in [`bikesched-examples`](../bikesched-examples/README.md) and the concept pages for the
details.

| Pattern | Use it when | Example |
|---|---|---|
| [Transactional outbox](#transactional-outbox) | a database change must trigger a call to another system | [Kotlin shop](../bikesched-examples/README.md#kotlin-shop) |
| [Deduplicating incoming messages](#deduplicating-incoming-messages) | a sender delivers the same message more than once | [Chat](../bikesched-examples/README.md#chat) |
| [Calling an unreliable API](#calling-an-unreliable-api) | a call can fail for technical reasons or with a business answer | [Outcome](../bikesched-examples/README.md#outcome) |
| [Staying within a rate limit](#staying-within-a-rate-limit) | a provider accepts only so many calls per second | [Rate limit](../bikesched-examples/README.md#rate-limit) |
| [Waiting for a webhook](#waiting-for-a-webhook) | another system reports back later, or not at all | [Poll](../bikesched-examples/README.md#poll) |
| [Approval with reminder and escalation](#approval-with-reminder-and-escalation) | a person has to decide, maybe not in time | [Approval](../bikesched-examples/README.md#approval) |
| [Fan-out/fan-in](#fan-outfan-in) | work splits into parallel parts that are combined | [Fan-out](../bikesched-examples/README.md#fan-out) |
| [Periodic work](#periodic-work) | a clean-up or sync must run on a schedule, once per run across all nodes | [Recurring](../bikesched-examples/README.md#recurring) |
| [Saga](#saga) | a sequence of calls must be undone if a later one fails | [Taxi booking](../bikesched-examples/README.md#taxi-booking) |

## Transactional outbox

Writing to your database and calling another system can't be atomic. Schedule a job in the same
transaction instead: it exists only if your change commits, and then makes the call.

```kotlin
transaction {
    Orders.insert { … }
    bikesched.createJob(PROCESS_ORDER, ProcessOrderPayload(orderId), connection = connection.connection as Connection)
}
```

- The job is the outbox entry: no outbox table, no relay process.
- The job runs at least once, so its calls need idempotency keys (next patterns).

Example: [Kotlin shop](../bikesched-examples/README.md#kotlin-shop). Details:
[Transactional scheduling](jobs/README.md#transactional-scheduling).

## Deduplicating incoming messages

Webhooks and message brokers deliver at least once. Use the sender's message id as the job's
idempotency key, and a redelivery schedules nothing:

```java
UUID jobId = bikesched.newJob(ANSWER_MESSAGE)
        .payload(message)
        .idempotencyKey(message.messageId())   // a redelivery returns the first job's id
        .schedule();
```

- Concurrent deliveries are deduplicated too: the second waits for the first transaction.
- A key stays taken while the job exists (its retention). Keys are unique per job type, so add the
  tenant to keys from several tenants.

Example: [Chat](../bikesched-examples/README.md#chat). Details:
[Idempotency keys](jobs/README.md#idempotency-keys).

## Calling an unreliable API

Let technical errors throw, so the job is retried. Return expected business answers as the step's
result, so they are stored and not retried. Pass an idempotency key derived from the step:

```java
ChargeOutcome outcome = job.step("charge-card", ChargeOutcome.class, step -> {
    try {
        return new Charged(provider.charge(order, step.jobId() + ":" + step.name()));
    } catch (CardDeclinedException e) {
        return new Declined(e.code());   // stored, not retried
    }
});
```

- An `IOException` fails the attempt; the retry repeats the step with the same key, so the provider
  charges once.
- A completed step is never repeated, also when a later step fails.

Example: [Outcome](../bikesched-examples/README.md#outcome). Details:
[Business outcomes as results](multi-step-jobs/steps.md#business-outcomes-as-results),
[Failures and retries](jobs/README.md#failures-and-retries).

## Staying within a rate limit

Take a token from a shared bucket before the call. Without one, postpone the job until the bucket
refills:

```java
job.step("send", String.class, _ -> {
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);   // Bucket4j, stored in PostgreSQL
    if (!probe.isConsumed()) {
        job.postpone(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }
    return provider.send(sms.to(), sms.text());
});
```

- Postponing uses up no attempt, records no failure and frees the node's slot while waiting.
- A bucket in the database holds the limit across all nodes.

Example: [Rate limit](../bikesched-examples/README.md#rate-limit). Details:
[Postponing](jobs/README.md#postponing).

## Waiting for a webhook

Start the remote operation in a step and wait for an event keyed by the id it returns. The webhook
endpoint publishes the event. If the webhook doesn't arrive in time, poll:

```java
String paymentId = job.step("create-payment", String.class, step -> provider.createPayment(…));
PaymentSettled settled = job.waitForEvent("await-webhook", PAYMENT_SETTLED, paymentId)
        .timeout(Duration.ofMinutes(5))
        .onFailure(failing -> failing.resolve(null))
        .receive();
if (settled == null) {
    settled = job.newPoll("poll-payment", PaymentSettled.class, Duration.ofMinutes(1))
            .run(_ -> provider.status(paymentId).map(Poll::ready).orElseGet(Poll::notReady));
}
```

```java
// webhook endpoint
bikesched.newEvent(PAYMENT_SETTLED, payment.paymentId())
        .payload(payment)
        .idempotencyKey(delivery.deliveryId())
        .publish();
```

- The job waits without a thread. A webhook arriving before the job waits is not lost: the wait
  receives it right away.
- The payment id is a stored step result, so every resume waits for the same payment.

Example: [Poll](../bikesched-examples/README.md#poll). Details: [Events](multi-step-jobs/events.md),
[Polling](multi-step-jobs/steps.md#polling).

## Approval with reminder and escalation

Wait for the decision with a timeout. A failure handler resolving with `null` turns the timeout into
the next stage instead of a failure:

```java
job.step("request-approval", () -> inbox.requestApproval(expense.manager(), expense));
Decision decision = job.waitForEvent("await-decision", EXPENSE_DECIDED, id)
        .timeout(Duration.ofDays(3))
        .onFailure(failing -> failing.resolve(null))
        .receive();
if (decision == null) {
    job.step("send-reminder", () -> inbox.remind(expense.manager(), expense));
    decision = job.waitForEvent("await-decision-after-reminder", EXPENSE_DECIDED, id)
            .timeout(Duration.ofDays(2))
            .onFailure(failing -> failing.resolve(null))
            .receive();
}
if (decision == null) {
    job.step("escalate", () -> inbox.requestApproval(FINANCE_LEAD, expense));
    decision = job.awaitEvent("await-escalated-decision", EXPENSE_DECIDED, id);
}
```

- Each stage is a separate wait step on the same event: a decision published after a stage timed
  out is received by the next one.
- The job holds no thread or slot during the days it waits.

Example: [Approval](../bikesched-examples/README.md#approval). Details:
[Timeouts](multi-step-jobs/events.md#timeouts).

## Fan-out/fan-in

Start one child job per part, then wait for each. The children run in parallel, on any node:

```java
List<UUID> children = album.files().stream()
        .map(file -> job.newJob(RESIZE_PHOTO).payload(new Photo(albumId, file)).stepName("resize " + file).schedule())
        .toList();
for (UUID child : children) {
    switch (job.waitForJob(child, Thumbnail.class)) {
        case JobResult.Succeeded<Thumbnail>(_, Thumbnail thumbnail) -> thumbnails.add(thumbnail);
        case JobResult.Failed<Thumbnail> _ -> skipped++;
        case JobResult<Thumbnail> other -> throw new IllegalStateException("Unexpected outcome: " + other);
    }
}
```

- Each child is started once: the start is a step of the parent.
- The parent waits without a thread. A failed child doesn't fail the parent; it decides from the
  outcome.

Example: [Fan-out](../bikesched-examples/README.md#fan-out). Details: [Child
jobs](multi-step-jobs/child-jobs.md).

## Periodic work

Register a recurring job on every node at startup. bikesched keeps exactly one occurrence pending,
so each run happens once, on whichever node claims it:

```java
bikesched.newJob("clean-up-sessions")
        .handler(_ -> sessions.removeExpired())
        .schedule(Recurrence.every(Duration.ofHours(1)));   // or daily/weekly, cron via bikesched-cron
```

- Registering is idempotent: no leader election or lock needed. Runs missed while no node was up
  are skipped, not caught up.
- Each occurrence is a regular job with retries and timeouts. To keep a long sync resumable, split
  it into steps.

Example: [Recurring](../bikesched-examples/README.md#recurring). Details:
[Recurring jobs](jobs/recurring-jobs.md).

## Saga

Give each step with a side effect a compensation. If the job is cancelled or fails for good, the
completed steps are undone, latest first:

```java
job.newStep("send-to-broker", Void.class)
        .compensateWith((_, _) -> broker.cancelRide(ride.rideId()))
        .run(_ -> { broker.requestRide(ride); return null; });
PaymentReservation reservation = job.newStep("reserve-payment", PaymentReservation.class)
        .compensateWith((_, stored) -> payments.release(stored))
        .run(_ -> payments.reserve(customer, offer.price(), offer.currency(), ride.rideId()));
job.step("approve-ride", RideApproval.class, _ -> broker.approveRide(ride.rideId(), offer, reservation));
```

- `bikesched.cancel(jobId, reason)` from outside and `job.cancel(reason)` inside run the same
  compensations.
- Compensations run at least once, so they must be idempotent.

Example: [Taxi booking](../bikesched-examples/README.md#taxi-booking). Details:
[Compensation](multi-step-jobs/steps.md#compensation).
