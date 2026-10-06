// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.LogCapture;
import org.bikesched.internal.job.HardTimeout;
import org.junit.jupiter.api.Test;

/**
 * The hard timeout ({@code hardTimeoutGrace}): an attempt whose handler ignores the interrupt of
 * its timeout is abandoned after the grace period; its thread keeps running but can no longer
 * write.
 */
class HardTimeoutTest extends AbstractBikeschedTest {

  private static final Duration TIMEOUT = Duration.ofMillis(200);
  private static final Duration GRACE = Duration.ofMillis(300);

  @Test
  void attemptIgnoringItsTimeoutIsAbandonedAndRetriedInItsFreedSlot() throws Exception {
    String type = unique("stuck");
    var release = new AtomicBoolean();
    var attempts = new AtomicInteger();
    var staleWrite = new CountDownLatch(1);
    List<Throwable> staleWriteFailures = new CopyOnWriteArrayList<>();
    var logs = LogCapture.of(HardTimeout.class);
    try (logs;
        Bikesched bikesched =
            testNode("node-hard-timeout").maxConcurrentJobs(1).hardTimeoutGrace(GRACE).build()) {
      UUID jobId =
          bikesched
              .newJob(type)
              .maxAttempts(2)
              .timeout(TIMEOUT)
              .handler(
                  job -> {
                    if (attempts.incrementAndGet() == 1) {
                      ignoreInterruptsUntil(release);
                      try {
                        job.step("late", String.class, _ -> "\"stale\"");
                      } catch (RuntimeException e) {
                        staleWriteFailures.add(e);
                      } finally {
                        staleWrite.countDown();
                      }
                    } else {
                      job.step("done", String.class, _ -> "\"retry\"");
                    }
                  })
              .schedule();

      // The retry runs in the slot the abandoned attempt freed (the node has only one).
      awaitState(jobId, "SUCCEEDED");
      assertThat(attempts).hasValue(2);
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and attempts = 2 and last_error like 'org.bikesched.BikeschedException:"
                      + " Job timed out after PT0.2S and did not stop within the hard timeout"
                      + " grace of PT0.3S%'"))
          .isOne();
      assertThat(logs.messages())
          .anyMatch(
              m ->
                  m.startsWith(
                          "Job "
                              + jobId
                              + " of type "
                              + type
                              + " did not stop within PT0.3S after its timeout of PT0.2S"
                              + " (attempt 1 of 2), abandoning thread")
                      && m.contains("1 abandoned thread(s) still running")
                      && m.contains("at " + HardTimeoutTest.class.getName()));

      // The stuck thread finally continues: its write is rejected, its outcome discarded.
      release.set(true);
      assertThat(staleWrite.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(staleWriteFailures)
          .singleElement()
          .satisfies(
              e -> assertThat(e).hasMessageContaining("is no longer running in this attempt's"));
      await()
          .untilAsserted(
              () ->
                  assertThat(logs.messages())
                      .anyMatch(
                          m ->
                              m.startsWith("Abandoned attempt 1 of job " + jobId)
                                  && m.endsWith(
                                      "its outcome is discarded (0 abandoned thread(s) still"
                                          + " running)")));
      assertThat(count("id = '" + jobId + "' and state = 'SUCCEEDED'")).isOne();
      assertThat(countRows("bikesched_steps", "job_id = '" + jobId + "'"))
          .as("only the retry's step")
          .isOne();
    } finally {
      release.set(true);
      execute("delete from bikesched_jobs where job_type = '" + type + "'");
    }
  }

  @Test
  void withoutHardTimeoutTheAttemptWaitsForItsHandler() throws Exception {
    String type = unique("stuck-no-hard-timeout");
    var release = new AtomicBoolean();
    try (Bikesched bikesched = testNode("node-no-hard-timeout").build()) {
      UUID jobId =
          bikesched
              .newJob(type)
              .maxAttempts(1)
              .timeout(TIMEOUT)
              .handler(_ -> ignoreInterruptsUntil(release))
              .schedule();
      awaitState(jobId, "RUNNING");

      Thread.sleep(TIMEOUT.plus(GRACE).plusMillis(500).toMillis());
      assertThat(count("id = '" + jobId + "' and state = 'RUNNING'"))
          .as("still running long after timeout and grace")
          .isOne();

      release.set(true);
      awaitState(jobId, "FAILED");
      assertThat(count("id = '" + jobId + "' and last_error like '%Job timed out after PT0.2S'"))
          .isOne();
    } finally {
      release.set(true);
      execute("delete from bikesched_jobs where job_type = '" + type + "'");
    }
  }

  @Test
  void graceMustBePositive() {
    assertThatThrownBy(() -> Bikesched.builder().hardTimeoutGrace(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hardTimeoutGrace must be positive");
  }

  /** Waits like non-interruptible code would: swallows every interrupt. */
  private static void ignoreInterruptsUntil(AtomicBoolean release) {
    while (!release.get()) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException ignored) {
        // ignored on purpose: the handler does not react to its timeout
      }
    }
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
