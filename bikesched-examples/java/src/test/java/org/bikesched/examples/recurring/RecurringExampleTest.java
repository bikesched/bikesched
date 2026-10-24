// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.Database;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.test.BikeschedTest;
import org.junit.jupiter.api.Test;

/** Runs the recurring example's job on two nodes against a real PostgreSQL. */
class RecurringExampleTest {

  private static final DataSource DATA_SOURCE = Database.dataSource();

  private final ByteArrayOutputStream output = new ByteArrayOutputStream();

  @Test
  void eachRunHappensOnceAcrossTwoNodes() throws SQLException {
    var example = new RecurringExample(new PrintStream(output, true, StandardCharsets.UTF_8));
    try (Bikesched first = node();
        Bikesched second = node()) {
      example.registerOn(first);
      example.registerOn(second);

      await().atMost(Duration.ofSeconds(30)).until(() -> example.runs() >= 4);
    }

    List<Long> gaps = gapsBetweenRunsMillis();
    assertThat(gaps).hasSizeGreaterThanOrEqualTo(3);
    assertThat(gaps).as("one run per second, never two at once").allMatch(gap -> gap >= 800);
    assertThat(pendingOccurrences()).as("one pending occurrence").isEqualTo(1);

    // Delete the pending occurrence, so it isn't left claimable for later tests.
    try (Bikesched cleanUp = node()) {
      assertThat(cleanUp.removeRecurringJob(RecurringExample.CLEAN_UP_SESSIONS)).isTrue();
    }
    assertThat(output.toString(StandardCharsets.UTF_8)).contains("removed expired sessions");
  }

  /** The time between the ends of consecutive succeeded occurrences (each run takes no time). */
  private static List<Long> gapsBetweenRunsMillis() throws SQLException {
    var gaps = new ArrayList<Long>();
    try (var connection = DATA_SOURCE.getConnection();
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "select extract(epoch from finished_at - lag(finished_at) over (order by finished_at))"
                    + " * 1000 from bikesched_jobs where name = '"
                    + RecurringExample.CLEAN_UP_SESSIONS
                    + "' and state = 'SUCCEEDED' order by finished_at")) {
      while (rows.next()) {
        double gap = rows.getDouble(1);
        if (!rows.wasNull()) {
          gaps.add(Math.round(gap));
        }
      }
    }
    return gaps;
  }

  private static int pendingOccurrences() throws SQLException {
    try (var connection = DATA_SOURCE.getConnection();
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "select count(*) from bikesched_jobs where name = '"
                    + RecurringExample.CLEAN_UP_SESSIONS
                    + "' and state = 'SCHEDULED'")) {
      rows.next();
      return rows.getInt(1);
    }
  }

  private static Bikesched node() {
    return BikeschedTest.testNode(DATA_SOURCE, "recurring")
        .jsonMapper(new JacksonJsonMapper())
        .build();
  }
}
