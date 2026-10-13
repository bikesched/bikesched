// SPDX-License-Identifier: Apache-2.0
package org.bikesched.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.core.ConditionTimeoutException;
import org.bikesched.Bikesched;
import org.bikesched.job.JobState;
import org.junit.jupiter.api.Test;

/** Tests {@link BikeschedTest#awaitJob} and {@link JobAwait}. */
class JobAwaitTest extends AbstractTestModuleTest {

  @Test
  void untilEndedWaitsForASucceededJob() {
    try (Bikesched bikesched = buildNode("job-await")) {
      String type = uniqueType("succeeds");
      bikesched.registerJobHandler(type, job -> {});
      UUID jobId = bikesched.newJob(type).schedule();

      var job = BikeschedTest.awaitJob(bikesched, jobId).untilEnded();

      assertThat(job.state()).isEqualTo(JobState.SUCCEEDED);
    }
  }

  @Test
  void untilStateWaitsForOneOfTheGivenStates() {
    try (Bikesched bikesched = buildNode("job-await")) {
      String type = uniqueType("fails");
      bikesched.registerJobHandler(
          type,
          job -> {
            throw new RuntimeException("boom");
          });
      UUID jobId = bikesched.newJob(type).maxAttempts(1).schedule();

      var job = BikeschedTest.awaitJob(bikesched, jobId).untilState(JobState.FAILED);

      assertThat(job.state()).isEqualTo(JobState.FAILED);
    }
  }

  @Test
  void untilSucceededWaitsForTheSucceededState() {
    try (Bikesched bikesched = buildNode("job-await")) {
      String type = uniqueType("succeeds");
      bikesched.registerJobHandler(type, job -> {});
      UUID jobId = bikesched.newJob(type).schedule();

      var job = BikeschedTest.awaitJob(bikesched, jobId).untilSucceeded();

      assertThat(job.state()).isEqualTo(JobState.SUCCEEDED);
    }
  }

  @Test
  void untilSucceededStepsWaitsForTheStepsToSucceedInOrder() {
    try (Bikesched bikesched = buildNode("job-await")) {
      String type = uniqueType("steps");
      bikesched.registerJobHandler(
          type,
          job -> {
            job.step("first", () -> {});
            job.step("second", () -> {});
          });
      UUID jobId = bikesched.newJob(type).schedule();

      List<org.bikesched.admin.StepInfo> steps =
          BikeschedTest.awaitJob(bikesched, jobId).untilSucceededSteps("first", "second");

      assertThat(steps)
          .extracting(org.bikesched.admin.StepInfo::name)
          .containsExactly("first", "second");
    }
  }

  @Test
  void timeoutIsAppliedAndTimesOutWithConditionTimeoutException() {
    try (Bikesched bikesched = buildNode("job-await")) {
      String type = uniqueType("never-due");
      bikesched.registerJobHandler(type, job -> {});
      // Scheduled far in the future: never reaches a terminal state within the short timeout.
      UUID jobId = bikesched.newJob(type).schedule(java.time.Instant.now().plusSeconds(3600));

      assertThatThrownBy(
              () ->
                  BikeschedTest.awaitJob(bikesched, jobId)
                      .timeout(Duration.ofMillis(200))
                      .untilEnded())
          .isInstanceOf(ConditionTimeoutException.class);
    }
  }
}
