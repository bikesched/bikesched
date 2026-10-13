// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.ratelimit.RateLimitExample.Sms;
import org.bikesched.examples.util.Database;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.test.BikeschedTest;
import org.junit.jupiter.api.Test;

/** Runs the rate-limit example's job on two nodes against a real PostgreSQL. */
class RateLimitExampleTest {

  private static final DataSource DATA_SOURCE = Database.dataSource();

  private final ByteArrayOutputStream output = new ByteArrayOutputStream();

  @Test
  void twoNodesStayWithinTheProvidersRateLimit() throws SQLException {
    var example =
        new RateLimitExample(DATA_SOURCE, new PrintStream(output, true, StandardCharsets.UTF_8));
    try (Bikesched first = node();
        Bikesched second = node()) {
      example.registerOn(first);
      example.registerOn(second);

      List<UUID> jobIds =
          IntStream.rangeClosed(1, 10)
              .mapToObj(i -> RateLimitExample.send(first, new Sms("+49 151 " + i, "sms-" + i)))
              .toList();

      for (UUID jobId : jobIds) {
        BikeschedTest.awaitJob(first, jobId).timeout(Duration.ofSeconds(60)).untilSucceeded();
      }
      assertThat(example.provider().rejected()).as("no call over the provider's limit").isZero();
      assertThat(example.provider().calls()).as("one call per message").isEqualTo(10);
      String ids = String.join("','", jobIds.stream().map(UUID::toString).toList());
      assertThat(count("bikesched_jobs where id in ('" + ids + "') and attempts = 1"))
          .as("postponing uses up no attempt")
          .isEqualTo(10);
      assertThat(
              count(
                  "bikesched_job_history where job_id in ('"
                      + ids
                      + "') and step_name = 'send' and state = 'POSTPONED'"))
          .as("the bucket postponed some steps")
          .isPositive();
      assertThat(output.toString(StandardCharsets.UTF_8)).contains("postponed by");
    }
  }

  private static int count(String from) throws SQLException {
    try (var connection = DATA_SOURCE.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("select count(*) from " + from)) {
      rows.next();
      return rows.getInt(1);
    }
  }

  private static Bikesched node() {
    return BikeschedTest.testNode(DATA_SOURCE, "ratelimit")
        .jobHistory(Duration.ofHours(1))
        .jsonMapper(new JacksonJsonMapper())
        .build();
  }
}
