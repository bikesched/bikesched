package org.bikesched.internal.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
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
    execute(
        "insert into bikesched_steps (job_id, name, state, attempts) select id, 's', 'SUCCEEDED', 1"
            + " from bikesched_jobs");

    assertThat(database.jobs().deleteFinishedJobs(Duration.ofHours(1), 1000)).isEqualTo(3);

    assertThat(count("job_type like 'old-%'")).isZero();
    assertThat(count("true")).isEqualTo(5);
    assertThat(countSteps("true")).as("steps deleted with their jobs").isEqualTo(5);
  }

  @Test
  void deletesAtMostTheLimit() throws SQLException {
    for (int i = 0; i < 5; i++) {
      insertJob("SUCCEEDED", "now() - interval '2 hours'", "old");
    }

    assertThat(database.jobs().deleteFinishedJobs(Duration.ofHours(1), 2)).isEqualTo(2);
    assertThat(database.jobs().deleteFinishedJobs(Duration.ofHours(1), 2)).isEqualTo(2);
    assertThat(database.jobs().deleteFinishedJobs(Duration.ofHours(1), 2)).isEqualTo(1);
    assertThat(count("true")).isZero();
  }

  private static void insertJob(String state, String finishedAt, String type) throws SQLException {
    String compensated = state.equals("COMPENSATING") ? "'CANCELLED'" : "null";
    execute(
        "insert into bikesched_jobs (id, job_type, state, compensated_state, finished_at) values"
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
