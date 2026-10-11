// SPDX-License-Identifier: Apache-2.0
package org.bikesched.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.event.listener.EventListener;
import org.bikesched.event.listener.EventPublication;
import org.junit.jupiter.api.Test;

/**
 * Idempotency keys of events ({@link NodeEventBuilder#idempotencyKey(String)}): while an event with
 * the same name, key and idempotency key is unexpired, publishing again returns it.
 */
class EventIdempotencyKeyTest extends AbstractBikeschedTest {

  @Test
  void publishWithTheSameKeyReturnsTheEarlierEvent() throws Exception {
    String eventName = unique("idempotent-event");
    List<EventPublication> published = new CopyOnWriteArrayList<>();
    EventListener listener =
        new EventListener() {
          @Override
          public void onEventPublished(EventPublication publication) {
            published.add(publication);
          }
        };
    var received = new CompletableFuture<String>();
    try (Bikesched bikesched = testNode("node-idempotent-event").eventListener(listener).build()) {
      UUID waiting =
          bikesched
              .newJob(unique("idempotent-event-receiver"))
              .handler(
                  job ->
                      received.complete(
                          job.waitForEvent("wait", eventName, "order-1", String.class).receive()))
              .schedule();
      awaitState(waiting, "WAITING");

      UUID first =
          bikesched
              .newEvent(eventName, "order-1")
              .payload("\"first\"")
              .idempotencyKey("delivery-1")
              .publish();
      UUID again =
          bikesched
              .newEvent(eventName, "order-1")
              .payload("\"second\"")
              .idempotencyKey("delivery-1")
              .publish();

      assertThat(again).isEqualTo(first);
      assertThat(countRows("bikesched_events", "name = '" + eventName + "'")).isOne();
      assertThat(received.get(10, TimeUnit.SECONDS)).isEqualTo("\"first\"");
      awaitState(waiting, "SUCCEEDED");
      assertThat(published).extracting(EventPublication::eventId).containsExactly(first);
    }
  }

  @Test
  void keyIsScopedToTheEventNameAndKey() throws Exception {
    String eventName = unique("idempotent-event-scope");
    String otherName = unique("idempotent-event-scope-other");
    try (Bikesched bikesched = node("node-idempotent-event-scope")) {
      UUID first = bikesched.newEvent(eventName, "order-1").idempotencyKey("k").publish();
      UUID otherKey = bikesched.newEvent(eventName, "order-2").idempotencyKey("k").publish();
      UUID otherEvent = bikesched.newEvent(otherName, "order-1").idempotencyKey("k").publish();
      UUID unkeyed = bikesched.newEvent(eventName, "order-1").publish();

      assertThat(List.of(first, otherKey, otherEvent, unkeyed)).doesNotHaveDuplicates();
    }
  }

  @Test
  void expiredEventNoLongerDeduplicates() throws Exception {
    String eventName = unique("idempotent-event-expired");
    try (Bikesched bikesched = node("node-idempotent-event-expired")) {
      UUID first =
          bikesched
              .newEvent(eventName, "order-1")
              .retention(Duration.ofMillis(200))
              .idempotencyKey("k")
              .publish();
      Thread.sleep(300);

      assertThat(bikesched.newEvent(eventName, "order-1").idempotencyKey("k").publish())
          .isNotEqualTo(first);

      // Without retention, the key has no effect.
      UUID unretained =
          bikesched
              .newEvent(eventName, "order-2")
              .retention(Duration.ZERO)
              .idempotencyKey("k")
              .publish();
      assertThat(
              bikesched
                  .newEvent(eventName, "order-2")
                  .retention(Duration.ZERO)
                  .idempotencyKey("k")
                  .publish())
          .isNotEqualTo(unretained);
    }
  }

  @Test
  void duplicateInTheCallersTransactionAndAfterItsCommit() throws Exception {
    String eventName = unique("idempotent-event-tx");
    try (Bikesched bikesched = node("node-idempotent-event-tx");
        Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      UUID first =
          bikesched
              .newEvent(eventName, "order-1")
              .idempotencyKey("k")
              .connection(connection)
              .publish();
      UUID inTransaction =
          bikesched
              .newEvent(eventName, "order-1")
              .idempotencyKey("k")
              .connection(connection)
              .publish();
      connection.commit();

      assertThat(inTransaction).isEqualTo(first);
      assertThat(bikesched.newEvent(eventName, "order-1").idempotencyKey("k").publish())
          .isEqualTo(first);
      assertThat(countRows("bikesched_events", "name = '" + eventName + "'")).isOne();
    }
  }

  private static Bikesched node(String name) {
    return testNode(name).autoStartPolling(false).build();
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
