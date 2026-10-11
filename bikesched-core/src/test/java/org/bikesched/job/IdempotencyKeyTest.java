// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.junit.jupiter.api.Test;

/**
 * Idempotency keys of jobs ({@link JobBuilder#idempotencyKey(String)}): unique per job type for as
 * long as the job exists, so scheduling again returns the existing job.
 */
class IdempotencyKeyTest extends AbstractBikeschedTest {

  @Test
  void scheduleWithTheSameKeyReturnsTheExistingJob() throws Exception {
    String type = unique("idempotent");
    try (Bikesched bikesched = node("node-idempotent")) {
      UUID first =
          bikesched
              .newJob(type)
              .payload("\"first\"")
              .handler(() -> {})
              .idempotencyKey("delivery-1")
              .schedule(Duration.ofHours(1));
      UUID again =
          bikesched
              .newJob(type)
              .idempotencyKey("delivery-1")
              .payload("\"second\"")
              .priority(5)
              .schedule();

      assertThat(again).isEqualTo(first);
      assertThat(count("type = '" + type + "'")).isOne();
      assertThat(count("id = '" + first + "' and payload = '\"first\"' and priority = 0"))
          .as("the duplicate's settings are ignored")
          .isOne();
      List<JobHistoryEntry> history = bikesched.findJobHistory(first);
      assertThat(history).hasSize(1);
      assertThat(history.getFirst().detail())
          .startsWith("due at ")
          .endsWith(", idempotency key delivery-1");
    } finally {
      deleteJobs(type);
    }
  }

  @Test
  void keyIsUniquePerJobType() throws Exception {
    String type = unique("idempotent-a");
    String other = unique("idempotent-b");
    try (Bikesched bikesched = node("node-idempotent-types")) {
      UUID a = bikesched.newJob(type).handler(() -> {}).idempotencyKey("k").schedule();
      UUID b = bikesched.newJob(other).handler(() -> {}).idempotencyKey("k").schedule();
      UUID unkeyed = bikesched.newJob(type).schedule();

      assertThat(b).isNotEqualTo(a);
      assertThat(unkeyed).isNotIn(a, b);
    } finally {
      deleteJobs(type);
      deleteJobs(other);
    }
  }

  @Test
  void reusedBuilderReturnsTheFirstJob() throws Exception {
    String type = unique("idempotent-reused");
    try (Bikesched bikesched = node("node-idempotent-reused")) {
      IdempotentJobBuilder<Object> builder =
          bikesched.newJob(type).handler(() -> {}).idempotencyKey("k");

      assertThat(builder.schedule(Duration.ofHours(1))).isEqualTo(builder.schedule());
      assertThat(count("type = '" + type + "'")).isOne();
    } finally {
      deleteJobs(type);
    }
  }

  @Test
  void keyStaysTakenAfterTheJobEndedUntilItIsDeleted() throws Exception {
    String succeeding = unique("idempotent-succeeded");
    String failing = unique("idempotent-failed");
    String cancelled = unique("idempotent-cancelled");
    try (Bikesched bikesched = testNode("node-idempotent-ended").build()) {
      UUID succeeded =
          bikesched.newJob(succeeding).handler(() -> {}).idempotencyKey("k").schedule();
      UUID failed =
          bikesched
              .newJob(failing)
              .maxAttempts(1)
              .handler(
                  () -> {
                    throw new IllegalStateException("boom");
                  })
              .idempotencyKey("k")
              .schedule();
      UUID cancel =
          bikesched
              .newJob(cancelled)
              .handler(job -> job.cancel("no"))
              .idempotencyKey("k")
              .schedule();
      awaitState(succeeded, "SUCCEEDED");
      awaitState(failed, "FAILED");
      awaitState(cancel, "CANCELLED");

      assertThat(bikesched.newJob(succeeding).idempotencyKey("k").schedule()).isEqualTo(succeeded);
      assertThat(bikesched.newJob(failing).idempotencyKey("k").schedule()).isEqualTo(failed);
      assertThat(bikesched.newJob(cancelled).idempotencyKey("k").schedule()).isEqualTo(cancel);

      // The job retention deleted the job: the key is free again.
      execute("delete from bikesched_jobs where id = '" + succeeded + "'");
      UUID next = bikesched.newJob(succeeding).idempotencyKey("k").schedule();
      assertThat(next).isNotEqualTo(succeeded);
      awaitState(next, "SUCCEEDED");
    } finally {
      deleteJobs(succeeding);
      deleteJobs(failing);
      deleteJobs(cancelled);
    }
  }

  @Test
  void duplicateWhileTheJobRunsReturnsItRightAway() throws Exception {
    String type = unique("idempotent-running");
    var running = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (Bikesched bikesched = testNode("node-idempotent-running").build()) {
      UUID first =
          bikesched
              .newJob(type)
              .handler(
                  job -> {
                    running.countDown();
                    release.await();
                  })
              .idempotencyKey("k")
              .schedule();
      assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

      assertThat(bikesched.newJob(type).idempotencyKey("k").schedule()).isEqualTo(first);
      release.countDown();
      awaitState(first, "SUCCEEDED");
      assertThat(count("type = '" + type + "'")).isOne();
    } finally {
      release.countDown();
      deleteJobs(type);
    }
  }

  @Test
  void duplicateInTheSameTransaction() throws Exception {
    String type = unique("idempotent-tx");
    try (Bikesched bikesched = node("node-idempotent-tx");
        Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      UUID first =
          bikesched
              .newJob(type)
              .handler(() -> {})
              .connection(connection)
              .idempotencyKey("k")
              .schedule(Duration.ofHours(1));
      UUID again = bikesched.newJob(type).idempotencyKey("k").connection(connection).schedule();
      connection.commit();

      assertThat(again).isEqualTo(first);
      assertThat(count("type = '" + type + "'")).isOne();
    } finally {
      deleteJobs(type);
    }
  }

  @Test
  void concurrentTransactionWaitsForTheFirstAndReturnsItsCommittedJob() throws Exception {
    String type = unique("idempotent-tx-commit");
    try (Bikesched bikesched = node("node-idempotent-tx-commit");
        Connection first = dataSource.getConnection();
        Connection second = dataSource.getConnection()) {
      first.setAutoCommit(false);
      second.setAutoCommit(false);
      UUID firstId =
          bikesched
              .newJob(type)
              .handler(() -> {})
              .idempotencyKey("k")
              .connection(first)
              .schedule(Duration.ofHours(1));

      CompletableFuture<UUID> secondId = scheduleAsync(bikesched, type, second);
      assertThatThrownBy(() -> secondId.get(300, TimeUnit.MILLISECONDS))
          .as("waits for the first transaction")
          .isInstanceOf(TimeoutException.class);
      first.commit();

      assertThat(secondId.get(10, TimeUnit.SECONDS)).isEqualTo(firstId);
      second.commit();
      assertThat(count("type = '" + type + "'")).isOne();
    } finally {
      deleteJobs(type);
    }
  }

  @Test
  void jobRolledBackLeavesTheKeyFree() throws Exception {
    String type = unique("idempotent-tx-rollback");
    try (Bikesched bikesched = node("node-idempotent-tx-rollback");
        Connection first = dataSource.getConnection();
        Connection second = dataSource.getConnection()) {
      first.setAutoCommit(false);
      second.setAutoCommit(false);
      UUID firstId =
          bikesched
              .newJob(type)
              .handler(() -> {})
              .idempotencyKey("k")
              .connection(first)
              .schedule(Duration.ofHours(1));

      CompletableFuture<UUID> secondId = scheduleAsync(bikesched, type, second);
      assertThatThrownBy(() -> secondId.get(300, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
      first.rollback();

      UUID stored = secondId.get(10, TimeUnit.SECONDS);
      second.commit();
      assertThat(stored).isNotEqualTo(firstId);
      assertThat(count("type = '" + type + "' and id = '" + stored + "'")).isOne();
      assertThat(count("type = '" + type + "'")).isOne();
    } finally {
      deleteJobs(type);
    }
  }

  @Test
  void blankKeysAreRejected() {
    try (Bikesched bikesched = node("node-idempotent-invalid")) {
      assertThatThrownBy(() -> bikesched.newJob("x").idempotencyKey(" "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("idempotency key must not be blank");
      assertThatThrownBy(() -> bikesched.newEvent("x", "k").idempotencyKey(""))
          .hasMessage("idempotency key must not be blank");
    }
  }

  private static CompletableFuture<UUID> scheduleAsync(
      Bikesched bikesched, String type, Connection connection) {
    return CompletableFuture.supplyAsync(
        () ->
            bikesched
                .newJob(type)
                .idempotencyKey("k")
                .connection(connection)
                .schedule(Duration.ofHours(1)));
  }

  /** A node that does not poll and records the job history. */
  private static Bikesched node(String name) {
    return testNode(name).autoStartPolling(false).jobHistory(Duration.ofDays(1)).build();
  }

  private static void deleteJobs(String type) throws Exception {
    execute(
        "delete from bikesched_job_history where job_id in"
            + " (select id from bikesched_jobs where type = '"
            + type
            + "')");
    execute("delete from bikesched_jobs where type = '" + type + "'");
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
