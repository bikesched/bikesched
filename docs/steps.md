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
- Steps cannot be nested: while a step handler runs, `job.step(...)`, `job.newStep(...)` and
  event waits of the same job throw an `IllegalStateException` (from any thread, so steps of one
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
  first step or event wait that has not completed. Then the compensations run, latest step first,
  and each compensated step becomes `COMPENSATED`. The job then ends `CANCELLED` or `FAILED`.
- A failing compensation is retried (up to `maxAttempts` compensation attempts, with the node's
  retry backoff); compensations that already succeeded are not run again. After that the job is
  `COMPENSATION_FAILED` and needs manual attention.
- Compensations run at least once, so they must be idempotent. The handler's code outside of
  steps runs again during the replay; `job.isCompensating()` tells it apart.

---

Next: [Recurring jobs](recurring-jobs.md) · Previous: [Jobs](jobs.md) ·
[Documentation index](index.md)
