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
- Events are stored in `bikesched_events` for their retention. A step that starts waiting after the
  event was published receives the latest one whose retention has not passed, right away. See
  [Retention](#retention).
- Waiting does not use up an attempt.
- Code outside steps runs again on every resume, so it must be cheap and deterministic.

## Typed events

An `EventType` names an event together with its payload class, shared by the publisher and the
waiting jobs:

```java
static final EventType<Payment> PAYMENT_RECEIVED = EventType.of("payment-received", Payment.class);

Payment payment = job.waitForEvent("await-payment", PAYMENT_RECEIVED, order.id())
        .timeout(Duration.ofDays(3), () -> null)   // optional; the result type is already known
        .receive();

bikesched.newEvent(PAYMENT_RECEIVED, order.id()).payload(payment).publish();   // payload(Payment) only
```

Only the name is stored, so events published with the string-based API reach typed waits and vice
versa. Like job payloads, event payloads should be objects rather than bare values or collections,
see [Payload design](configuration.md#payload-design).

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

## Retention

An event's retention only matters for steps that start waiting **after** it was published: steps
already waiting receive it when it is published, whatever its retention. It defaults to the
publishing node's `eventRetention` (7 days) and can be set per event:

```java
bikesched.newEvent("driver-accepted", rideId).payload(offer).retention(Duration.ofMinutes(5)).publish();
bikesched.newEvent("location-updated", rideId).payload(location).retention(Duration.ZERO).publish();
```

- The expiry (`expires_at`) is fixed when the event is published, so all nodes agree on it,
  whatever their own `eventRetention`.
- `Duration.ZERO` delivers the event only to the steps waiting right now; later waits never receive
  it. Useful for signals that make no sense once missed.
- Expired events are no longer received, and every polling node deletes them every
  `eventCleanUpInterval` (default 1 hour). Steps that received an event keep its payload and id.

## Consistency

Waiting, suspending and publishing lock the event's name and key for the length of their
transaction (a PostgreSQL advisory lock), so an event published while a job starts waiting is never
missed. Cancelling a waiting job marks its wait `CANCELLED`; it no longer receives events.

---

Next: [Configuration](configuration.md) · Previous: [Recurring jobs](recurring-jobs.md) ·
[Documentation index](index.md)
