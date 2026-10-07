// SPDX-License-Identifier: Apache-2.0
package org.bikesched.micrometer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.job.JobState;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A node with a {@link MicrometerJobListener} and a {@link MicrometerEventListener} records its
 * jobs and events.
 */
class MicrometerNodeTest {

  /** One container for this test class; Ryuk removes it when the JVM ends. */
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

  private static final PGSimpleDataSource DATA_SOURCE = new PGSimpleDataSource();

  static {
    POSTGRES.start();
    DATA_SOURCE.setUrl(POSTGRES.getJdbcUrl());
    DATA_SOURCE.setUser(POSTGRES.getUsername());
    DATA_SOURCE.setPassword(POSTGRES.getPassword());
  }

  @Test
  void recordsTheJobsOfANode() {
    var registry = new SimpleMeterRegistry();
    String type = "metrics-" + UUID.randomUUID();
    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(DATA_SOURCE)
            .nodeName("micrometer-" + UUID.randomUUID())
            .pollInterval(Duration.ofMillis(50))
            .jobListener(new MicrometerJobListener(registry))
            .build()) {
      bikesched.registerJobHandler(type, _ -> {});
      UUID jobId = bikesched.newJob(type).schedule();

      await()
          .atMost(Duration.ofSeconds(15))
          .untilAsserted(
              () -> {
                assertThat(bikesched.findJob(jobId).orElseThrow().state())
                    .isEqualTo(JobState.SUCCEEDED);
                assertThat(
                        registry
                            .get("bikesched.job.attempts")
                            .tags("job.type", type, "outcome", "succeeded")
                            .timer()
                            .count())
                    .isOne();
              });
      assertThat(registry.get("bikesched.job.claim.delay").tags("job.type", type).timer().count())
          .isOne();
      assertThat(registry.get("bikesched.jobs.active").gauge().value()).isZero();
    }
  }

  @Test
  void recordsTheEventsOfANode() {
    var registry = new SimpleMeterRegistry();
    String eventName = "metrics-event-" + UUID.randomUUID();
    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(DATA_SOURCE)
            .nodeName("micrometer-" + UUID.randomUUID())
            .autoStartPolling(false)
            .eventListener(new MicrometerEventListener(registry))
            .build()) {
      bikesched.newEvent(eventName, "k").publish();

      assertThat(
              registry
                  .get("bikesched.events.published")
                  .tags("event.name", eventName, "source", "node")
                  .counter()
                  .count())
          .isOne();
      assertThat(
              registry
                  .get("bikesched.event.deliveries")
                  .tags("event.name", eventName)
                  .counter()
                  .count())
          .isZero();
    }
  }
}
