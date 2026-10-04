# bikesched examples

Runnable examples of [bikesched](../README.md), one package per scenario. Each example has a `main`
that runs against the PostgreSQL from the repository's `compose.yaml`, and a test that runs against
PostgreSQL 18 with Testcontainers. Payloads, step results and events are records, mapped to JSON
by [`bikesched-jackson`](../docs/configuration.md#jackson).

## Running

```shell
docker compose up -d                      # from the repository root
./gradlew :bikesched-examples:run         # honors POSTGRES_PORT, like docker compose
./gradlew :bikesched-examples:test        # the example tests, against Testcontainers
```

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
