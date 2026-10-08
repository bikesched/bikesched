# Steps and compensation

Steps make the work inside a job durable: a completed step is not repeated when the job is retried
or resumed, and it can declare how to undo itself.

## Durable steps

Steps work like durable functions. A step that completed in an earlier attempt is not executed
again; its stored result is returned instead:

```java
bikesched.registerJobHandler("order", job -> {
    Order order = job.payload(Order.class);
    Payment payment = job.step("charge-card", Payment.class, _ -> payments.charge(order));
    job.step("send-confirmation", Void.class, _ -> {
        mailer.send(order, payment);   // if this fails, the retry does not charge the card again
        return null;                   // Void steps store no result
    });
});
```

- The result is mapped by the `JsonMapper` and stored in `bikesched_steps` right after the step
  completes.
- Step names identify steps across attempts, so they must be unique within a job and called in a
  deterministic way.
- The step handler receives the `Step` (job id, name and attempt number), e.g. for idempotency
  keys.
- Code outside of steps runs again on every attempt, so it should be cheap and deterministic.
- Steps cannot be nested: while a step handler runs, `job.step(...)`, `job.newStep(...)`, event
  waits and sleeps of the same job throw an `IllegalStateException` (from any thread, so steps of one
  job cannot run in parallel either). An inner step would complete durably even if the outer step
  failed, and its retry would skip it. Run the steps one after the other instead. The same applies
  inside [compensations](#compensation).

### Idempotency keys

Steps run at least once: a completed step is not repeated, but a step whose side effect happened
before its completion was stored runs again, e.g. after a crash right after the side effect, or on
a node that lost its job (see [Nodes](configuration.md#nodes)) while another node runs the step
too. bikesched stores only one result, but cannot undo the side effect. Pass an idempotency key
derived from the step to external systems, so they recognize a repeated call:

```java
Payment payment = job.step("charge-card", Payment.class, step ->
        payments.charge(order, step.jobId() + ":" + step.name()));   // same key in every attempt
```

Use the job id and step name, not the attempt number, which changes between attempts.

### Business outcomes as results

A step that throws fails, and the job is retried. That suits technical errors (a timeout, a
connection reset), but not expected business outcomes: a declined payment stays declined on every
retry, uses up the job's attempts and, once they are exhausted, starts its compensations. Return
such outcomes as the step's result instead, with a sealed type for success and the expected
errors:

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
@JsonSubTypes({@JsonSubTypes.Type(Charged.class), @JsonSubTypes.Type(Declined.class)})
sealed interface ChargeOutcome permits Charged, Declined {}
record Charged(String receiptId) implements ChargeOutcome {}
record Declined(String code, String reason) implements ChargeOutcome {}

ChargeOutcome outcome = job.step("charge-card", ChargeOutcome.class, step -> {
    var response = payments.charge(order, step.jobId() + ":" + step.name());
    return response.approved()
            ? new Charged(response.receiptId())
            : new Declined(response.declineCode(), response.message());   // stored, not retried
});
switch (outcome) {
    case Charged c -> job.step("send-receipt", Void.class, _ -> {
        mailer.sendReceipt(order, c.receiptId());
        return null;
    });
    case Declined d -> job.cancel("payment declined: " + d.code());
}
```

- The outcome is stored like any step result, so a later attempt or resume takes the same branch.
- Exceptions stay for what a retry may fix.
- The `JsonMapper` must restore the concrete type: with Jackson, declare the subtypes as above.

## Sleeping

A handler can pause for a duration or until a point in time, as a durable step. Like an
[event wait](events.md), the attempt ends at the sleep and the job is suspended in state `WAITING`,
without holding a thread or a concurrency slot; once the wake-up time has passed, the handler runs
again from the top, completed steps are skipped and the sleep returns:

```java
bikesched.registerJobHandler("trial", job -> {
    Account account = job.payload(Account.class);
    job.step("send-welcome", Void.class, _ -> { mail.welcome(account); return null; });
    job.sleep("trial-period", Duration.ofDays(14));
    job.step("send-trial-ended", Void.class, _ -> { mail.trialEnded(account); return null; });
});

job.sleepUntil("until-check-in", booking.checkIn().minus(Duration.ofDays(1)));
```

- The step name identifies the sleep across runs, like any step name; it must be unique within the
  job.
- The first run stores the wake-up time with the step (`wait_until`, computed by the database for
  `sleep`). Later runs ignore the argument, so a duration changed in a deploy or a time computed
  from `Instant.now()` doesn't move a sleep that already started.
- `sleepUntil` with a time that has passed returns right away; the step is stored as completed.
- The node that suspended the job wakes up at the wake-up time; other nodes find it with their
  next-due query, see [Due jobs start right away](configuration.md#due-jobs-start-right-away).
- Sleeping does not use up an attempt. Cancelling a sleeping job marks the step `CANCELLED` and runs
  the compensations of its completed steps.
- Sleeping inside a step handler or a compensation is rejected, like nested steps.

## Polling

A handler can poll an external service until a result is ready, as one durable step. Each run of
the handler checks once: if the result is not ready, the attempt ends at the poll and the job is
suspended in state `WAITING` until the next check, without holding a thread or a concurrency slot,
like a [sleep](#sleeping). Once a check is ready, the poll returns the value, which is stored and
returned on later runs without checking again:

```java
Report report = job.newPoll("await-report", Report.class, Duration.ofSeconds(30))
        .timeout(Duration.ofHours(2))   // optional
        .run(step -> switch (reports.status(reportId)) {
            case Done d -> Poll.ready(d.report());
            case Pending _ -> Poll.notReady();
        });
```

| The poll handler… | Step | Job |
|---|---|---|
| returns `Poll.ready(value)` | `SUCCEEDED`, the value stored | continues |
| returns `Poll.notReady()` | `WAITING` until the next check | suspended, the attempt given back |
| returns `Poll.notReady()` after the deadline | `TIMED_OUT` | fails with a `PollTimeoutException`, or gets the timeout handler's result |
| throws | `FAILED` | the attempt fails and is retried (with the node's retry backoff); the next attempt checks right away |

- A poll is one row in `bikesched_steps` for all its checks; the step's `attempts` counts them, and
  the poll handler receives it as `step.attempts()`. Checks that are not ready don't use up
  attempts.
- The interval is required. For a growing one, pass a `RetryBackoff` instead of a `Duration`: it
  is called after each check that is not ready, with the number of that check, e.g.
  `job.newPoll("await-report", Report.class, RetryBackoff.exponential(Duration.ofSeconds(5),
  Duration.ofMinutes(5)))`. `Poll.notReady(Duration)` sets the time until the next check for one
  check, e.g. from a `Retry-After` header.
- `timeout(Duration)` stops polling after the given time, counted from the first check; the last
  check runs at the deadline. Then the job fails without further attempts with a
  `PollTimeoutException`, or, with `timeout(Duration, handler)`, `run` returns the handler's
  result. Like an event wait's timeout handler, it is not stored and runs on every replay.
  Without a timeout, the poll checks until it is ready or the job is cancelled.
- The deadline stored on the first check wins: a timeout changed in a deploy doesn't move a poll
  that already started. Polling again after a timeout needs a new step name.
- A due check runs when it is due, like a [sleep](#sleeping) wakes up. Each check is a claim and
  a suspension of the job; for checks every second or faster, loop inside a
  [step](#durable-steps) instead.
- An expected negative answer (e.g. the report failed) is a ready value too, see
  [Business outcomes as results](#business-outcomes-as-results); exceptions are for errors a retry
  may fix.
- The [job history](jobs.md#job-history) records the poll's first wait and its outcome (`ready
  after 3 checks`), not every check.
- Polling inside a step handler or a compensation is rejected, like nested steps. Cancelling a
  polling job marks the poll `CANCELLED` and runs the compensations of its completed steps.

Polling with a loop of steps and sleeps (`check-1`, `wait-1`, `check-2`, …) works too, but adds two
rows per check, and every run replays all earlier checks. Failing the attempt with
`job.fail(…).retryAfter(…)` while the result is not ready uses up attempts and counts as a failure.

### Webhooks with a polling fallback

When a service sends a webhook but may lose it, wait for the webhook for a short time and poll if
it doesn't arrive:

```java
PaymentSettled webhook = job.waitForEvent("await-webhook", PAYMENT_SETTLED, paymentId)
        .timeout(Duration.ofSeconds(30), () -> null)
        .receive();
PaymentSettled settled = webhook != null
        ? webhook
        : job.newPoll("poll-payment", PaymentSettled.class, Duration.ofMinutes(1))
                .timeout(Duration.ofHours(1))
                .run(_ -> provider.status(paymentId).map(Poll::ready).orElseGet(Poll::notReady));
```

A timeout handler may also run the poll itself. The
[poll example](../bikesched-examples/README.md#poll) shows both paths.

## Compensation

Steps with side effects can declare a compensation that undoes them if the job is cancelled or
fails permanently (a saga). Compensation is optional, per step:

```java
bikesched.registerJobHandler("order", job -> {
    Order order = job.payload(Order.class);
    Reservation reservation = job.newStep("reserve-payment", Reservation.class)
            .compensateWith((step, r) -> payments.release(r))   // gets the step and its stored result
            .run(_ -> payments.reserve(order));
    job.step("ship", Void.class, _ -> { shipping.ship(order, reservation); return null; });
});

bikesched.cancel(orderJobId, "order withdrawn");   // releases the reservation if it was made
```

`newStep(name, Class).run(handler)` without `compensateWith` is the same as `step(...)`.

- A job without completed compensable steps is cancelled or failed right away.
- Otherwise it becomes `COMPENSATING`. A node replays its handler in compensation mode: completed
  steps return their stored results and register their compensations, and the replay ends at the
  first step, event wait or sleep that has not completed. Then the compensations run, latest step first,
  and each compensated step becomes `COMPENSATED`. The job then ends `CANCELLED` or `FAILED`.
- A failing compensation is retried (up to `maxAttempts` compensation attempts, with the node's
  retry backoff); compensations that already succeeded are not run again. After that the job is
  `COMPENSATION_FAILED` and needs manual attention.
- Compensations run at least once, so they must be idempotent. The handler's code outside of
  steps runs again during the replay; `job.isCompensating()` tells it apart.

---

Next: [Recurring jobs](recurring-jobs.md) · Previous: [Jobs](jobs.md) ·
[Documentation index](index.md)
