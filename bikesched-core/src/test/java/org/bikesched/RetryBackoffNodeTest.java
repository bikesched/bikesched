package org.bikesched;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bikesched.internal.job.JobProcessor;
import org.junit.jupiter.api.Test;

/** The node-wide {@link RetryBackoff} applied to failed attempts. */
class RetryBackoffNodeTest extends AbstractBikeschedTest {

  private static final Duration BACKOFF = Duration.ofMillis(600);

  @Test
  void thrownExceptionsAreRetriedAfterTheBackoff() throws Exception {
    List<Instant> starts = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched = node("node-backoff-throw", RetryBackoff.fixed(BACKOFF))) {
      UUID jobId =
          bikesched
              .newJob("backoff-throw")
              .handler(
                  job -> {
                    starts.add(Instant.now());
                    if (job.attempts() == 1) {
                      throw new IllegalStateException("boom");
                    }
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");
    }
    assertThat(starts).hasSize(2);
    assertThat(Duration.between(starts.get(0), starts.get(1)))
        .isGreaterThanOrEqualTo(BACKOFF.minusMillis(50));
  }

  @Test
  void backoffGetsTheNumberOfTheFailedAttempt() throws Exception {
    List<Integer> failedAttempts = new CopyOnWriteArrayList<>();
    RetryBackoff recording =
        attempt -> {
          failedAttempts.add(attempt);
          return Duration.ZERO;
        };
    try (Bikesched bikesched = node("node-backoff-attempts", recording)) {
      UUID jobId =
          bikesched
              .newJob("backoff-attempts")
              .maxAttempts(3)
              .handler(
                  job -> {
                    throw new IllegalStateException("boom");
                  })
              .schedule();

      awaitState(jobId, "FAILED");
    }
    assertThat(failedAttempts)
        .as("consulted for each failed attempt, also the last one")
        .containsExactly(1, 2, 3);
  }

  @Test
  void handlerFailureWithoutRetryAfterUsesTheBackoff() throws Exception {
    try (Bikesched bikesched =
        node("node-backoff-fail", RetryBackoff.fixed(Duration.ofMinutes(10)))) {
      UUID jobId =
          bikesched
              .newJob("backoff-fail")
              .handler(job -> job.fail("busy").remainingAttempts(3))
              .schedule();

      awaitFailedAttempt(jobId);

      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and attempts = 1 and max_attempts = 4"
                      + " and scheduled_at > now() + interval '9 minutes'"))
          .isEqualTo(1);
    }
  }

  @Test
  void explicitRetryAfterWinsOverTheBackoff() throws Exception {
    List<Instant> starts = new CopyOnWriteArrayList<>();
    try (Bikesched bikesched =
        node("node-backoff-explicit", RetryBackoff.fixed(Duration.ofMinutes(10)))) {
      UUID jobId =
          bikesched
              .newJob("backoff-explicit")
              .handler(
                  job -> {
                    starts.add(Instant.now());
                    if (job.attempts() == 1) {
                      job.fail().retryAfter(Duration.ZERO);
                    }
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");
    }
    assertThat(starts).hasSize(2);
  }

  @Test
  void failingBackoffRetriesAsSoonAsPossible() throws Exception {
    var logs = LogCapture.of(JobProcessor.class);
    UUID jobId;
    try (logs;
        Bikesched bikesched =
            node(
                "node-backoff-broken",
                attempt -> {
                  throw new IllegalStateException("broken backoff");
                })) {
      jobId =
          bikesched
              .newJob("backoff-broken")
              .handler(
                  job -> {
                    if (job.attempts() == 1) {
                      throw new IllegalStateException("boom");
                    }
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");
    }
    assertThat(logs.messages())
        .contains("Retry backoff failed for job " + jobId + ", retrying as soon as possible");
  }

  /** Waits until the job's first attempt failed and it is scheduled again. */
  private static void awaitFailedAttempt(UUID jobId) {
    await()
        .untilAsserted(
            () ->
                assertThat(count("id = '" + jobId + "' and state = 'SCHEDULED' and attempts = 1"))
                    .as("first attempt failed and the job is scheduled again")
                    .isEqualTo(1));
  }

  private static Bikesched node(String name, RetryBackoff backoff) {
    return Bikesched.builder()
        .dataSource(dataSource)
        .nodeName(name)
        .pollInterval(Duration.ofMillis(50))
        .retryBackoff(backoff)
        .build();
  }
}
