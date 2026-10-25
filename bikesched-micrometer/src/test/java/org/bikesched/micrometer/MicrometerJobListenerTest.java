// SPDX-License-Identifier: Apache-2.0
package org.bikesched.micrometer;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bikesched.job.listener.JobAttemptOutcome;
import org.bikesched.job.listener.JobAttemptResult;
import org.bikesched.job.listener.JobClaim;
import org.junit.jupiter.api.Test;

/** The meters {@link MicrometerJobListener} records for the listener calls. */
class MicrometerJobListenerTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MicrometerJobListener listener =
      new MicrometerJobListener(registry, Tags.of("node", "n1"));

  @Test
  void recordsClaimsAttemptsAndActiveJobs() {
    JobClaim first = claim("mail", Duration.ofMillis(300));
    JobClaim second = claim("mail", Duration.ofMillis(100));

    listener.onJobClaimed(first);
    listener.onJobClaimed(second);
    assertThat(gauge("bikesched.jobs.active")).isEqualTo(2);

    listener.onAttemptEnded(
        new JobAttemptResult(first, JobAttemptOutcome.SUCCEEDED, Duration.ofMillis(50), false));
    listener.onAttemptEnded(
        new JobAttemptResult(second, JobAttemptOutcome.RETRYING, Duration.ofMillis(20), false));

    assertThat(gauge("bikesched.jobs.active")).isZero();
    var delay =
        registry.get("bikesched.job.claim.delay").tags("node", "n1", "job.name", "mail").timer();
    assertThat(delay.count()).isEqualTo(2);
    assertThat(delay.max(TimeUnit.MILLISECONDS)).isEqualTo(300);
    var succeeded =
        registry
            .get("bikesched.job.attempts")
            .tags("node", "n1", "job.name", "mail", "outcome", "succeeded")
            .timer();
    assertThat(succeeded.count()).isOne();
    assertThat(succeeded.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(50);
    assertThat(
            registry
                .get("bikesched.job.attempts")
                .tags("job.name", "mail", "outcome", "retrying")
                .timer()
                .count())
        .isOne();
    assertThat(registry.find("bikesched.job.attempts.abandoned").counter()).isNull();
  }

  @Test
  void countsAbandonedAttemptsUntilTheirThreadReturns() {
    JobClaim claim = claim("stuck", Duration.ZERO);
    listener.onJobClaimed(claim);
    listener.onAttemptEnded(
        new JobAttemptResult(claim, JobAttemptOutcome.FAILED, Duration.ofSeconds(1), true));

    assertThat(gauge("bikesched.jobs.active")).isZero();
    assertThat(gauge("bikesched.jobs.abandoned")).isOne();
    assertThat(
            registry
                .get("bikesched.job.attempts.abandoned")
                .tags("node", "n1", "job.name", "stuck")
                .counter()
                .count())
        .isOne();

    listener.onAbandonedAttemptReturned(claim);
    assertThat(gauge("bikesched.jobs.abandoned")).isZero();
  }

  private double gauge(String name) {
    return registry.get(name).tags("node", "n1").gauge().value();
  }

  private static JobClaim claim(String type, Duration delay) {
    return new JobClaim(UUID.randomUUID(), type, null, 1, false, false, delay);
  }
}
