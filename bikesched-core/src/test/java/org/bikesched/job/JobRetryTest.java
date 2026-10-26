// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.HistoryTesting;
import org.bikesched.history.ActorType;
import org.bikesched.history.HistoryEntry;
import org.bikesched.history.JobEntry;
import org.junit.jupiter.api.Test;

/** Retrying failed jobs with {@link Bikesched#retry(UUID)}. */
class JobRetryTest extends AbstractBikeschedTest {

  @Test
  void failedJobIsRetriedAndContinuesAfterItsCompletedSteps() throws Exception {
    var reserved = new AtomicInteger();
    var charged = new AtomicInteger();
    var fixed = new AtomicBoolean();
    try (Bikesched bikesched = testNode("node-retry").history(Duration.ofDays(1)).build()) {
      UUID jobId =
          bikesched
              .newJob("retry-" + UUID.randomUUID())
              .maxAttempts(1)
              .handler(
                  job -> {
                    job.step(
                        "reserve",
                        String.class,
                        ignored -> "\"r-" + reserved.incrementAndGet() + "\"");
                    job.step(
                        "charge",
                        String.class,
                        ignored -> {
                          charged.incrementAndGet();
                          if (!fixed.get()) {
                            throw new IllegalStateException("card declined");
                          }
                          return "\"p-1\"";
                        });
                  })
              .schedule();
      awaitState(jobId, "FAILED");

      fixed.set(true);
      assertThat(bikesched.retry(jobId)).isTrue();
      awaitState(jobId, "SUCCEEDED");

      assertThat(reserved).as("completed step not run again").hasValue(1);
      assertThat(charged).hasValue(2);
      assertThat(count("id = '" + jobId + "' and attempts = 1 and finished_at is not null"))
          .isOne();
      List<HistoryEntry> history = bikesched.findJobHistory(jobId);
      assertThat(HistoryTesting.actionsAndStates(history))
          .filteredOn(action -> !action.contains(" "))
          .containsExactly(
              "SCHEDULED:SCHEDULED",
              "CLAIMED:RUNNING",
              "FAILED:FAILED",
              "RETRIED:SCHEDULED",
              "CLAIMED:RUNNING",
              "SUCCEEDED:SUCCEEDED");
      JobEntry retried = (JobEntry) HistoryTesting.entry(history, "RETRIED");
      assertThat(retried.attempt()).isZero();
      assertThat(retried.actorType()).isEqualTo(ActorType.APPLICATION);
      assertThat(bikesched.retry(jobId)).as("only failed jobs").isFalse();
    }
  }

  @Test
  void unknownJobIsNotRetried() throws Exception {
    try (Bikesched bikesched = testNode("node-retry-unknown").build()) {
      assertThat(bikesched.retry(UUID.randomUUID())).isFalse();
    }
  }
}
