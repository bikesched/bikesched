// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.event;

import java.time.Duration;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.Database;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.job.JobState;
import org.bikesched.test.BikeschedTest;
import org.junit.jupiter.api.Test;

/** Runs the event example's job against a real PostgreSQL. */
class EventExampleTest {

  private static final DataSource DATA_SOURCE = Database.dataSource();

  @Test
  void jobReceivesTheEventItsServicePublished() {
    try (Bikesched bikesched = node()) {
      var jobId = new EventExample(bikesched).scheduleJob(null);

      BikeschedTest.awaitJob(bikesched, jobId).untilSucceeded();
    }
  }

  @Test
  void jobWaitsForTheEventPublishedLater() {
    try (Bikesched bikesched = node()) {
      var jobId = new EventExample(bikesched).scheduleJob(Duration.ofSeconds(3));

      BikeschedTest.awaitJob(bikesched, jobId)
          .timeout(Duration.ofSeconds(3))
          .untilState(JobState.WAITING);
      BikeschedTest.awaitJob(bikesched, jobId).untilSucceeded();
    }
  }

  private static Bikesched node() {
    return BikeschedTest.testNode(DATA_SOURCE, "event").jsonMapper(new JacksonJsonMapper()).build();
  }
}
