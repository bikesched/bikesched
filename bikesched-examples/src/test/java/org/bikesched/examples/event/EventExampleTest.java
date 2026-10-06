package org.bikesched.examples.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.Database;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.job.JobState;
import org.junit.jupiter.api.Test;

/** Runs the event example's job against a real PostgreSQL. */
class EventExampleTest {

  private static final DataSource DATA_SOURCE = Database.dataSource();

  @Test
  void jobReceivesTheEventItsServicePublished() {
    try (Bikesched bikesched = node()) {
      var jobId = new EventExample(bikesched).scheduleJob(null);

      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
      assertThat(bikesched.findJob(jobId).orElseThrow().state()).isEqualTo(JobState.SUCCEEDED);
    }
  }

  @Test
  void jobWaitsForTheEventPublishedLater() {
    try (Bikesched bikesched = node()) {
      var jobId = new EventExample(bikesched).scheduleJob(Duration.ofSeconds(3));

      await()
          .atMost(Duration.ofSeconds(3))
          .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState.WAITING::equals);
      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
      assertThat(bikesched.findJob(jobId).orElseThrow().state()).isEqualTo(JobState.SUCCEEDED);
    }
  }

  private static Bikesched node() {
    return Bikesched.builder()
        .dataSource(DATA_SOURCE)
        .nodeName("event-" + UUID.randomUUID())
        .jsonMapper(new JacksonJsonMapper())
        .build();
  }
}
