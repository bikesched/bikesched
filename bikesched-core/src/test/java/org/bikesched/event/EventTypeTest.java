// SPDX-License-Identifier: Apache-2.0
package org.bikesched.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.PaymentJsonMapper;
import org.bikesched.PaymentJsonMapper.Payment;
import org.junit.jupiter.api.Test;

/** Events with a payload class: {@link EventType}, next to the string-based API. */
class EventTypeTest extends AbstractBikeschedTest {

  @Test
  void typedWaitReceivesTheMappedPayload() throws Exception {
    EventType<Payment> paid = EventType.of(unique("paid"), Payment.class);
    String type = unique("typed-wait");
    var received = new CompletableFuture<Payment>();
    try (Bikesched bikesched = node("node-event-type")) {
      UUID jobId =
          bikesched
              .newJob(type)
              .handler(
                  job -> received.complete(job.waitForEvent("await", paid, "order-1").receive()))
              .schedule();
      awaitState(jobId, "WAITING");

      bikesched.newEvent(paid, "order-1").payload(new Payment("order-1", 100)).publish();

      assertThat(received.get(10, TimeUnit.SECONDS)).isEqualTo(new Payment("order-1", 100));
      awaitState(jobId, "SUCCEEDED");
    }
  }

  @Test
  void awaitEventReturnsThePayloadWithoutReceive() throws Exception {
    EventType<Payment> paid = EventType.of(unique("paid"), Payment.class);
    String type = unique("await-event");
    var runs = new java.util.concurrent.atomic.AtomicInteger();
    var received = new CompletableFuture<Payment>();
    try (Bikesched bikesched = node("node-await-event")) {
      UUID jobId =
          bikesched
              .newJob(type)
              .handler(
                  job -> {
                    runs.incrementAndGet();
                    received.complete(job.awaitEvent("await", paid, "order-2"));
                  })
              .schedule();
      awaitState(jobId, "WAITING");

      bikesched.newEvent(paid, "order-2").payload(new Payment("order-2", 250)).publish();

      assertThat(received.get(10, TimeUnit.SECONDS)).isEqualTo(new Payment("order-2", 250));
      awaitState(jobId, "SUCCEEDED");
      assertThat(runs).as("suspended, then resumed by the event").hasValue(2);
    }
  }

  @Test
  void awaitEventFailsTheJobAfterTheDefaultEventTimeout() throws Exception {
    EventType<Payment> paid = EventType.of(unique("paid"), Payment.class);
    String type = unique("await-event-timeout");
    try (Bikesched bikesched =
        testNode("node-await-event-timeout")
            .jsonMapper(new PaymentJsonMapper())
            .defaultEventTimeout(Duration.ofMillis(300))
            .build()) {
      UUID jobId =
          bikesched.newJob(type).handler(job -> job.awaitEvent("await", paid, "never")).schedule();

      awaitState(jobId, "FAILED");
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and last_error like 'Waiting for event "
                      + paid.name()
                      + "/never in step ''await'' timed out after PT0.3S%'"))
          .isOne();
    }
  }

  @Test
  void typedWaitReturnsTheTimeoutResultOnceTheDeadlinePassed() throws Exception {
    EventType<Payment> paid = EventType.of(unique("paid-late"), Payment.class);
    String type = unique("typed-timeout");
    var received = new CompletableFuture<Payment>();
    try (Bikesched bikesched = node("node-event-type-timeout")) {
      bikesched
          .newJob(type)
          .handler(
              job ->
                  received.complete(
                      job.waitForEvent("await", paid, "order-2")
                          .timeout(Duration.ofMillis(200))
                          .onFailure(failing -> failing.resolve(new Payment("none", 0)))
                          .receive()))
          .schedule();

      assertThat(received.get(10, TimeUnit.SECONDS)).isEqualTo(new Payment("none", 0));
    }
  }

  @Test
  void eventPublishedWithTheStringApiReachesATypedWait() throws Exception {
    String name = unique("paid-mixed");
    EventType<Payment> paid = EventType.of(name, Payment.class);
    String type = unique("typed-mixed");
    var received = new CompletableFuture<Payment>();
    try (Bikesched bikesched = node("node-event-type-mixed")) {
      UUID jobId =
          bikesched
              .newJob(type)
              .handler(
                  job -> received.complete(job.waitForEvent("await", paid, "order-3").receive()))
              .schedule();
      awaitState(jobId, "WAITING");

      bikesched.newEvent(name, "order-3").payload(new Payment("order-3", 300)).publish();

      assertThat(received.get(10, TimeUnit.SECONDS)).isEqualTo(new Payment("order-3", 300));
    }
  }

  @Test
  void eventTypeNeedsANameAndAPayloadClass() {
    assertThatThrownBy(() -> EventType.of("", Payment.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("event name must not be blank");
    assertThatThrownBy(() -> EventType.of(null, Payment.class))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("event name");
  }

  private static Bikesched node(String name) {
    return testNode(name).jsonMapper(new PaymentJsonMapper()).build();
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
