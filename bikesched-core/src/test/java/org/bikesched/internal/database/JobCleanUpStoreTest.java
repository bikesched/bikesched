// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class JobCleanUpStoreTest extends AbstractStoreTest {

  @Test
  void deletesEndedJobsPastTheRetentionWithTheirSteps() throws SQLException {
    for (String state : new String[] {"SUCCEEDED", "FAILED", "CANCELLED"}) {
      insertJob(state, "now() - interval '2 hours'", "old-" + state);
      insertJob(state, "now() - interval '10 minutes'", "recent-" + state);
    }
    insertJob("SCHEDULED", "null", "pending");
    insertJob("COMPENSATING", "null", "compensating");
    insertJob("COMPENSATION_FAILED", "now() - interval '2 hours'", "compensation-failed");
    execute(
        "insert into bikesched_steps (id, job_id, name, state, attempts, finished_at)"
            + " select gen_random_uuid(), id, 's', 'SUCCEEDED', 1, now() from bikesched_jobs");

    assertThat(database.jobs().deleteFinishedJobs(null, null, Duration.ofHours(1), 1000).count())
        .isEqualTo(3);

    assertThat(count("type like 'old-%'")).isZero();
    assertThat(count("true")).as("COMPENSATION_FAILED jobs are kept").isEqualTo(6);
    assertThat(countSteps("true")).as("steps deleted with their jobs").isEqualTo(6);
  }

  @Test
  void deletesAtMostTheLimitOldestFirst() throws SQLException {
    for (int i = 1; i <= 5; i++) {
      insertJob("SUCCEEDED", "now() - interval '" + (i + 1) + " hours'", "old-" + i);
    }
    Instant secondOldest = finishedAt("old-4");

    DeletedBatch first = database.jobs().deleteFinishedJobs(null, null, Duration.ofHours(1), 2);

    assertThat(first.count()).isEqualTo(2);
    assertThat(count("type in ('old-5', 'old-4')")).as("the oldest two").isZero();
    assertThat(first.last()).isEqualTo(secondOldest);
  }

  @Test
  void deletesOnlyJobsFinishedAtOrAfterTheGivenTime() throws SQLException {
    insertJob("SUCCEEDED", "now() - interval '3 hours'", "older");
    insertJob("SUCCEEDED", "now() - interval '2 hours'", "newer");
    Instant older = finishedAt("older");

    DeletedBatch batch =
        database.jobs().deleteFinishedJobs(finishedAt("newer"), null, Duration.ofHours(1), 10);

    assertThat(batch.count()).isOne();
    assertThat(count("type = 'older'")).isOne();
    assertThat(database.jobs().deleteFinishedJobs(null, null, Duration.ofHours(1), 10).last())
        .isEqualTo(older);
  }

  @Test
  void deleteAllContinuesUntilABatchIsNotFull() throws SQLException {
    for (int i = 1; i <= 5; i++) {
      insertJob("SUCCEEDED", "now() - interval '" + (i + 1) + " hours'", "old-" + i);
    }
    List<@Nullable Instant> afters = new ArrayList<>();
    List<@Nullable Instant> cutoffs = new ArrayList<>();

    int deleted =
        DeletedBatch.deleteAll(
            2,
            (after, cutoff) -> {
              afters.add(after);
              cutoffs.add(cutoff);
              return database.jobs().deleteFinishedJobs(after, cutoff, Duration.ofHours(1), 2);
            });

    assertThat(deleted).isEqualTo(5);
    assertThat(count("true")).isZero();
    assertThat(afters).hasSize(3).first().isNull();
    assertThat(afters.get(1)).as("resumes where the last batch ended").isNotNull();
    assertThat(cutoffs.get(0)).isNull();
    assertThat(cutoffs.get(1)).as("the first batch's cutoff").isNotNull().isEqualTo(cutoffs.get(2));
  }

  @Test
  void aGivenCutoffReplacesTheRetention() throws SQLException {
    insertJob("SUCCEEDED", "now() - interval '3 hours'", "older");
    insertJob("SUCCEEDED", "now() - interval '2 hours'", "newer");
    Instant newer = finishedAt("newer");

    DeletedBatch batch = database.jobs().deleteFinishedJobs(null, newer, Duration.ofHours(1), 10);

    assertThat(batch.count()).isOne();
    assertThat(batch.cutoff()).isEqualTo(newer);
    assertThat(count("type = 'newer'")).as("finished at the cutoff: kept").isOne();
  }

  private static Instant finishedAt(String type) throws SQLException {
    try (var connection = dataSource.getConnection();
        var query =
            connection.prepareStatement("select finished_at from bikesched_jobs where type = ?")) {
      query.setString(1, type);
      try (var rs = query.executeQuery()) {
        rs.next();
        return rs.getObject(1, OffsetDateTime.class).toInstant();
      }
    }
  }

  private static void insertJob(String state, String finishedAt, String type) throws SQLException {
    String compensated =
        state.startsWith("COMPENSATION") || state.equals("COMPENSATING") ? "'CANCELLED'" : "null";
    execute(
        "insert into bikesched_jobs (id, type, state, compensation_end_state, finished_at) values"
            + " (gen_random_uuid(), '"
            + type
            + "', '"
            + state
            + "', "
            + compensated
            + ", "
            + finishedAt
            + ")");
  }
}
