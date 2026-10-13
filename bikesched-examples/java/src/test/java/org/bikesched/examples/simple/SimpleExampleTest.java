// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.simple;

import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.Database;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.test.BikeschedTest;
import org.junit.jupiter.api.Test;

/** Runs the simple example's job against a real PostgreSQL. */
class SimpleExampleTest {

  private static final DataSource DATA_SOURCE = Database.dataSource();

  @Test
  void handlerReceivesThePayload() {
    try (Bikesched bikesched =
        BikeschedTest.testNode(DATA_SOURCE, "simple").jsonMapper(new JacksonJsonMapper()).build()) {

      var jobId = new SimpleExample(bikesched).scheduleJob();
      BikeschedTest.awaitJob(bikesched, jobId).untilEnded();
    }
  }
}
