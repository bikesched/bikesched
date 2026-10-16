// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.LogCapture;
import org.bikesched.internal.job.JobProcessor;
import org.junit.jupiter.api.Test;

/**
 * A job released while its handler runs (as for a node considered dead): the handler's next step
 * write fails, and the attempt ends without recording anything, even if the handler swallows the
 * exception.
 */
class JobLockLostTest extends AbstractBikeschedTest {

  @Test
  void attemptOfAReleasedJobIsAbandoned() throws Exception {
    String type = unique("lock-lost");
    var firstStepDone = new CountDownLatch(1);
    var released = new CountDownLatch(1);
    var secondStepFailure = new AtomicReference<Exception>();
    var logs = LogCapture.of(JobProcessor.class);
    try (logs;
        Bikesched bikesched = testNode("node-lock-lost").build()) {
      UUID jobId =
          bikesched
              .newJob(type)
              .handler(
                  job -> {
                    job.step("first", String.class, ignored -> "\"1\"");
                    firstStepDone.countDown();
                    released.await(10, TimeUnit.SECONDS);
                    try {
                      job.step("second", String.class, ignored -> "\"2\"");
                    } catch (RuntimeException e) {
                      secondStepFailure.set(e); // swallowed: the attempt is abandoned anyway
                    }
                  })
              .schedule();
      assertThat(firstStepDone.await(10, TimeUnit.SECONDS)).isTrue();
      // Released, as by the node clean-up of another node, and not due again during the test.
      execute(
          "update bikesched_jobs set state = 'SCHEDULED', locked_by = null, lock_token = null,"
              + " locked_at = null,"
              + " scheduled_at = now() + interval '1 day' where id = '"
              + jobId
              + "'");
      released.countDown();

      await()
          .untilAsserted(
              () ->
                  assertThat(logs.messages())
                      .anyMatch(
                          m ->
                              m.startsWith("Job " + jobId + " of type " + type)
                                  && m.endsWith("abandoning attempt 1 without recording it")));
      assertThat(secondStepFailure.get())
          .hasMessageContaining("is no longer running in this attempt's claim");
      assertThat(count("id = '" + jobId + "' and state = 'SCHEDULED' and attempts = 1"))
          .as("left as released, not SUCCEEDED")
          .isEqualTo(1);
      assertThat(countRows("bikesched_steps", "job_id = '" + jobId + "'"))
          .as("only the first step")
          .isEqualTo(1);
    } finally {
      execute("delete from bikesched_jobs where type = '" + type + "'");
    }
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
