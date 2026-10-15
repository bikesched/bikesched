// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.LogCapture;
import org.bikesched.internal.DefaultBikesched;
import org.junit.jupiter.api.Test;

/** The job retention (default 7 days): ended jobs are deleted with their steps. */
class JobCleanUpTest extends AbstractBikeschedTest {

  @Test
  void endedJobsOlderThanTheRetentionAreDeleted() throws Exception {
    String type = unique("job-cleanup");
    UUID oldJob = UUID.randomUUID();
    UUID recentJob = UUID.randomUUID();
    UUID pendingJob = UUID.randomUUID();
    execute(
        "insert into bikesched_jobs (id, type, state, finished_at) values ('"
            + oldJob
            + "', '"
            + type
            + "', 'SUCCEEDED', now() - interval '2 hours'), ('"
            + recentJob
            + "', '"
            + type
            + "', 'FAILED', now()), ('"
            + pendingJob
            + "', '"
            + type
            + "', 'SCHEDULED', null)");
    execute(
        "update bikesched_jobs set scheduled_at = now() + interval '1 day' where id = '"
            + pendingJob
            + "'");
    execute(
        "insert into bikesched_steps (job_id, name, state, attempts) values ('"
            + oldJob
            + "', 's', 'SUCCEEDED', 1)");
    var logs = LogCapture.of(DefaultBikesched.class);
    try (logs;
        Bikesched bikesched =
            testNode("node-job-cleanup")
                .jobRetention(Duration.ofHours(1))
                .jobCleanUpInterval(Duration.ofMillis(100))
                .build()) {
      await().untilAsserted(() -> assertThat(bikesched.findJob(oldJob)).isEmpty());
      assertThat(countRows("bikesched_steps", "job_id = '" + oldJob + "'")).isZero();
      assertThat(bikesched.findJob(recentJob)).isPresent();
      assertThat(bikesched.findJob(pendingJob)).isPresent();
      assertThat(logs.messages())
          .anyMatch(m -> m.matches("Deleted \\d+ job\\(s\\) finished more than PT1H ago"));
    } finally {
      execute("delete from bikesched_jobs where type = '" + type + "'");
    }
  }

  @Test
  void defaultRetentionIsSevenDays() throws Exception {
    String type = unique("job-default-retention");
    UUID oldJob = UUID.randomUUID();
    UUID recentJob = UUID.randomUUID();
    execute(
        "insert into bikesched_jobs (id, type, state, finished_at) values ('"
            + oldJob
            + "', '"
            + type
            + "', 'SUCCEEDED', now() - interval '8 days'), ('"
            + recentJob
            + "', '"
            + type
            + "', 'SUCCEEDED', now() - interval '6 days')");
    try (Bikesched bikesched =
        testNode("node-job-default-retention").jobCleanUpInterval(Duration.ofMillis(100)).build()) {
      await().untilAsserted(() -> assertThat(bikesched.findJob(oldJob)).isEmpty());
      assertThat(bikesched.findJob(recentJob)).isPresent();
    } finally {
      execute("delete from bikesched_jobs where type = '" + type + "'");
    }
  }

  @Test
  void retentionAndIntervalMustBePositive() {
    assertThatThrownBy(() -> Bikesched.builder().jobRetention(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("jobRetention must be positive: PT0S");
    assertThatThrownBy(() -> Bikesched.builder().jobCleanUpInterval(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("jobCleanUpInterval must be positive: PT-1S");
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
