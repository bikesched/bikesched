// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.simple;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.Database;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.job.JobState;
import org.junit.jupiter.api.Test;

/** Runs the simple example's job against a real PostgreSQL. */
class SimpleExampleTest {

  private static final DataSource DATA_SOURCE = Database.dataSource();

  @Test
  void handlerReceivesThePayload() {
    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(DATA_SOURCE)
            .nodeName("simple-" + UUID.randomUUID())
            .jsonMapper(new JacksonJsonMapper())
            .build()) {

      var jobId = new SimpleExample(bikesched).scheduleJob();
      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
    }
  }
}
