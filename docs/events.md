# Events

A handler can wait for an event as a durable step. If the event hasn't been published yet, the
attempt ends and the job is suspended in state `WAITING`, without holding a thread or a concurrency
slot. Publishing the event resumes it, and the handler runs again from the top: completed steps are
skipped, and the wait now returns the event.

## Waiting and publishing

```java
bikesched.registerJobHandler("order", job -> {
    Order order = job.payload(Order.class);
    Payment payment = job.waitForEvent("await-payment", "payment-received", order.id())
            .receive(Payment.class);
    job.step("ship", Void.class, _ -> { shipping.ship(order, payment); return null; });
});

// e.g. in a payment webhook; .connection(connection) publishes in your own transaction
List<ReceivedEvent> received = bikesched.newEvent("payment-received", order.id())
        .payload(payment)
        .publish();   // the steps that received the event, with their job ids
```

- An event is identified by its name and key and goes to every step waiting for it.
- `receive(Payment.class)` returns the payload mapped by the `JsonMapper`; `receive(event -> …)`
  gets the whole `Event` (name, key, payload) instead.
- Events are stored in `bikesched_events` for the `eventRetention` period (default 7 days). A step
  that starts waiting after the event was published receives the latest one right away.
- Waiting does not use up an attempt.
- Code outside steps runs again on every resume, so it must be cheap and deterministic.

## Timeouts

To stop waiting after a while, add a timeout; its handler's result is returned instead of the
event:

```java
Payment payment = job.waitForEvent("await-payment", "payment-received", order.id())
        .<Payment>timeout(Duration.ofDays(3), () -> null)   // the type is needed for a null result
        .receive(Payment.class);
```

Without a timeout of its own, a wait uses the node's `defaultEventTimeout`, if set: once it passes,
the job fails permanently with an `EventTimeoutException`.

## Consistency

Waiting, suspending and publishing lock the event's name and key for the length of their
transaction (a PostgreSQL advisory lock), so an event published while a job starts waiting is never
missed. Cancelling a waiting job marks its wait `CANCELLED`; it no longer receives events.

---

Next: [Configuration](configuration.md) · Previous: [Recurring jobs](recurring-jobs.md) ·
[Documentation index](index.md)
