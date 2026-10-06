// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.annotation;

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

/** Runs the annotation example's jobs against a real PostgreSQL. */
class AnnotationExampleTest {

  private static final DataSource DATA_SOURCE = Database.dataSource();

  @Test
  void annotatedMethodsRunTheJobs() {
    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(DATA_SOURCE)
            .nodeName("annotation-" + UUID.randomUUID())
            .jsonMapper(new JacksonJsonMapper())
            .build()) {

      for (UUID jobId : new AnnotationExample(bikesched).scheduleJobs()) {
        await()
            .atMost(Duration.ofSeconds(30))
            .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
        assertThat(bikesched.findJob(jobId).orElseThrow().state()).isEqualTo(JobState.SUCCEEDED);
      }
    }
  }
}
