// SPDX-License-Identifier: Apache-2.0
package org.bikesched.test;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bikesched.Bikesched;
import org.bikesched.admin.StepInfo;
import org.bikesched.job.JobInfo;
import org.bikesched.job.JobState;

/**
 * Waits for the outcome of one job, created by {@link BikeschedTest#awaitJob(Bikesched, UUID)}.
 * Every {@code until*} method polls {@code bikesched} (every 50 ms, up to the {@link
 * #timeout(Duration) timeout}, 10 seconds by default) until the condition holds, or throws {@link
 * org.awaitility.core.ConditionTimeoutException} once the timeout passes. Awaitility backs this
 * class internally; it is not part of this class's own API.
 */
public final class JobAwait {

  private final Bikesched bikesched;
  private final UUID jobId;
  private Duration timeout = BikeschedTest.DEFAULT_TIMEOUT;

  JobAwait(Bikesched bikesched, UUID jobId) {
    this.bikesched = bikesched;
    this.jobId = jobId;
  }

  /**
   * Overrides the default timeout of 10 seconds.
   *
   * @param timeout the new timeout
   * @return this instance, for chaining
   */
  public JobAwait timeout(Duration timeout) {
    this.timeout = timeout;
    return this;
  }

  /**
   * Waits until the job's state satisfies {@link JobState#isEnded()}.
   *
   * @return the job, read once it had ended
   */
  public JobInfo untilEnded() {
    return untilJob(
        info -> {
          if (!info.state().isEnded()) {
            throw new AssertionError(
                "job " + jobId + " expected to have ended but was " + info.state());
          }
        });
  }

  /**
   * Waits until the job is in one of the given states.
   *
   * @param states the expected states
   * @return the job, read once it was in one of {@code states}
   */
  public JobInfo untilState(JobState... states) {
    var expected = Set.of(states);
    return untilJob(
        info -> {
          if (!expected.contains(info.state())) {
            throw new AssertionError(
                "job " + jobId + " expected state " + expected + " but was " + info.state());
          }
        });
  }

  /**
   * Like {@code untilState(JobState.SUCCEEDED)}.
   *
   * @return the job, read once it had succeeded
   */
  public JobInfo untilSucceeded() {
    return untilState(JobState.SUCCEEDED);
  }

  /**
   * Waits until the job's succeeded steps, in the order they started (see {@link
   * org.bikesched.admin.Admin#findSteps(UUID)}), are exactly the given names.
   *
   * @param names the expected names of the succeeded steps, in order
   * @return the job's succeeded steps, read once they matched
   */
  public List<StepInfo> untilSucceededSteps(String... names) {
    var expected = List.of(names);
    var result = new AtomicReference<List<StepInfo>>();
    await()
        .atMost(timeout)
        .pollInterval(BikeschedTest.POLL_INTERVAL)
        .untilAsserted(
            () -> {
              var succeeded = succeededSteps(bikesched.admin().findSteps(jobId));
              var actualNames = succeeded.stream().map(StepInfo::name).toList();
              if (!actualNames.equals(expected)) {
                throw new AssertionError(
                    "job "
                        + jobId
                        + " expected succeeded steps "
                        + expected
                        + " but were "
                        + actualNames);
              }
              result.set(succeeded);
            });
    return result.get();
  }

  private static List<StepInfo> succeededSteps(List<StepInfo> steps) {
    return steps.stream().filter(step -> "SUCCEEDED".equals(step.state())).toList();
  }

  private JobInfo untilJob(Consumer<JobInfo> check) {
    var result = new AtomicReference<JobInfo>();
    await()
        .atMost(timeout)
        .pollInterval(BikeschedTest.POLL_INTERVAL)
        .untilAsserted(
            () -> {
              var info =
                  bikesched
                      .findJob(jobId)
                      .orElseThrow(() -> new AssertionError("job " + jobId + " not found"));
              check.accept(info);
              result.set(info);
            });
    return result.get();
  }
}
