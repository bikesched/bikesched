// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The node-wide {@code statementTimeout}: jobs run as usual with it (its effect on a slow statement
 * is covered by the store-level {@code StatementTimeoutTest}).
 */
class StatementTimeoutNodeTest extends AbstractBikeschedTest {

  @Test
  void jobsRunWithAStatementTimeout() {
    try (Bikesched bikesched =
        testNode("node-statement-timeout").statementTimeout(Duration.ofSeconds(5)).build()) {
      UUID jobId =
          bikesched
              .newJob(unique("statement-timeout"))
              .handler(job -> job.step("s", String.class, _ -> "\"done\""))
              .schedule();

      awaitState(jobId, "SUCCEEDED");
      assertThat(countRows("bikesched_steps", "job_id = '" + jobId + "'")).isEqualTo(1);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  void statementTimeoutMustBePositive() {
    assertThatThrownBy(() -> Bikesched.builder().statementTimeout(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("statementTimeout must be positive: PT0S");
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
