// SPDX-License-Identifier: Apache-2.0
package org.bikesched.micrometer;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.bikesched.event.listener.EventPublication;
import org.bikesched.event.listener.EventSource;
import org.junit.jupiter.api.Test;

/** The meters {@link MicrometerEventListener} records for the listener calls. */
class MicrometerEventListenerTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MicrometerEventListener listener =
      new MicrometerEventListener(registry, Tags.of("node", "n1"));

  @Test
  void countsPublicationsBySourceAndTheirDeliveries() {
    listener.onEventPublished(publication("paid", EventSource.NODE, 2));
    listener.onEventPublished(publication("paid", EventSource.NODE, 0));
    listener.onEventPublished(publication("paid", EventSource.JOB, 1));

    assertThat(
            registry
                .get("bikesched.events.published")
                .tags("node", "n1", "event.name", "paid", "source", "node")
                .counter()
                .count())
        .isEqualTo(2);
    assertThat(
            registry
                .get("bikesched.events.published")
                .tags("event.name", "paid", "source", "job")
                .counter()
                .count())
        .isOne();
    assertThat(
            registry
                .get("bikesched.event.deliveries")
                .tags("node", "n1", "event.name", "paid")
                .counter()
                .count())
        .isEqualTo(3);
  }

  @Test
  void separatesEventNames() {
    listener.onEventPublished(publication("paid", EventSource.NODE, 0));
    listener.onEventPublished(publication("shipped", EventSource.NODE, 0));

    assertThat(registry.get("bikesched.events.published").counters()).hasSize(2);
    assertThat(
            registry
                .get("bikesched.events.published")
                .tags("event.name", "shipped")
                .counter()
                .count())
        .isOne();
  }

  private static EventPublication publication(String name, EventSource source, int received) {
    return new EventPublication(UUID.randomUUID(), name, null, source, received);
  }
}
