// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.annotation;

import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.Database;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.test.BikeschedTest;
import org.junit.jupiter.api.Test;

/** Runs the annotation example's jobs against a real PostgreSQL. */
class AnnotationExampleTest {

  private static final DataSource DATA_SOURCE = Database.dataSource();

  @Test
  void annotatedMethodsRunTheJobs() {
    try (Bikesched bikesched =
        BikeschedTest.testNode(DATA_SOURCE, "annotation")
            .jsonMapper(new JacksonJsonMapper())
            .build()) {

      for (UUID jobId : new AnnotationExample(bikesched).scheduleJobs()) {
        BikeschedTest.awaitJob(bikesched, jobId).untilSucceeded();
      }
    }
  }
}
