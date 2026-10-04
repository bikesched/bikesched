package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.LogCapture;
import org.bikesched.internal.job.DefaultJobFailure;
import org.bikesched.internal.job.JobProcessor;
import org.junit.jupiter.api.Test;

/** Failing attempts explicitly via {@link Job#fail(String)}, with retry settings. */
class JobFailTest extends AbstractBikeschedTest {

  @Test
  void failedAttemptIsRetriedAsSoonAsPossibleByDefault() throws Exception {
    try (Bikesched bikesched = node("node-fail-default")) {
      var calls = new AtomicInteger();
      UUID jobId =
          bikesched
              .newJob("fail-default")
              .handler(
                  job -> {
                    if (calls.incrementAndGet() == 1) {
                      job.fail("provider busy");
                    }
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");

      assertThat(calls).hasValue(2);
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and attempts = 2 and max_attempts = 3 and last_error = 'provider busy'"))
          .isEqualTo(1);
    }
  }

  @Test
  void retryAfterDelaysTheNextAttempt() throws Exception {
    var logs = LogCapture.of(JobProcessor.class);
    List<Instant> attemptStarts = new CopyOnWriteArrayList<>();
    UUID jobId;
    try (logs;
        Bikesched bikesched = node("node-fail-later")) {
      jobId =
          bikesched
              .newJob("fail-later")
              .handler(
                  job -> {
                    attemptStarts.add(Instant.now());
                    if (job.attempts() == 1) {
                      job.fail("not ready yet").retryAfter(Duration.ofMillis(700));
                    }
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");
    }
    assertThat(attemptStarts).hasSize(2);
    assertThat(Duration.between(attemptStarts.get(0), attemptStarts.get(1)))
        .as("second attempt waited for the delay")
        .isGreaterThanOrEqualTo(Duration.ofMillis(650));
    assertThat(logs.events())
        .filteredOn(
            e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage()
                        .matches(
                            "Job "
                                + jobId
                                + " of type fail-later failed \\(attempt 1 of 3\\), it will be"
                                + " retried at .*: not ready yet"))
        .hasSize(1);
  }

  @Test
  void retriesSetTheNewMaximumNumberOfAttempts() throws Exception {
    try (Bikesched bikesched = node("node-fail-retries")) {
      var calls = new AtomicInteger();
      UUID jobId =
          bikesched
              .newJob("fail-retries")
              .maxAttempts(1)
              .handler(
                  job -> {
                    calls.incrementAndGet();
                    if (job.attempts() == 1) {
                      job.fail().remainingAttempts(2); // 1 + 2 = 3 attempts in total
                    } else {
                      job.fail();
                    }
                  })
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(calls).hasValue(3);
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and attempts = 3 and max_attempts = 3"
                      + " and last_error = 'Job failed by its handler'"))
          .isEqualTo(1);
    }
  }

  @Test
  void zeroRetriesFailTheJobRightAway() throws Exception {
    try (Bikesched bikesched = node("node-fail-fatal")) {
      var calls = new AtomicInteger();
      UUID jobId =
          bikesched
              .newJob("fail-fatal")
              .maxAttempts(5)
              .handler(
                  job -> {
                    calls.incrementAndGet();
                    job.fail("invalid input")
                        .remainingAttempts(0)
                        .retryAfter(Duration.ofMinutes(1));
                  })
              .schedule();

      awaitState(jobId, "FAILED");
      Thread.sleep(200); // a few poll cycles

      assertThat(calls).hasValue(1);
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and attempts = 1 and max_attempts = 1 and last_error = 'invalid input'"))
          .isEqualTo(1);
    }
  }

  @Test
  void explicitFailureWinsOverExceptionAndTimeout() throws Exception {
    var logs = LogCapture.of(JobProcessor.class);
    UUID thrown;
    UUID timedOut;
    try (logs;
        Bikesched bikesched = node("node-fail-precedence")) {
      thrown =
          bikesched
              .newJob("fail-then-throw")
              .handler(
                  job -> {
                    job.fail("explicit").remainingAttempts(0);
                    throw new IllegalStateException("thrown afterwards");
                  })
              .schedule();
      timedOut =
          bikesched
              .newJob("fail-then-hang")
              .timeout(Duration.ofMillis(100))
              .handler(
                  job -> {
                    job.fail("explicit").remainingAttempts(0);
                    Thread.sleep(Duration.ofMinutes(5));
                  })
              .schedule();

      awaitState(thrown, "FAILED");
      awaitState(timedOut, "FAILED");
    }
    assertThat(count("id in ('" + thrown + "', '" + timedOut + "') and last_error = 'explicit'"))
        .isEqualTo(2);
    assertThat(logs.messages())
        .contains(
            "Job "
                + thrown
                + " of type fail-then-throw was failed by its handler, ignoring the exception"
                + " thrown afterwards");
  }

  @Test
  void cancellationWinsOverFailure() throws Exception {
    try (Bikesched bikesched = node("node-fail-cancel")) {
      UUID jobId =
          bikesched
              .newJob("fail-then-cancel")
              .handler(
                  job -> {
                    job.fail("failed").remainingAttempts(5);
                    job.cancel("cancelled");
                  })
              .schedule();

      awaitState(jobId, "CANCELLED");

      assertThat(count("id = '" + jobId + "' and attempts = 1 and last_error = 'cancelled'"))
          .isEqualTo(1);
    }
  }

  @Test
  void retrySettingsAreValidated() {
    var failure = new DefaultJobFailure("x");
    assertThatThrownBy(() -> failure.remainingAttempts(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("remainingAttempts must be >= 0: -1");
    assertThatThrownBy(() -> failure.retryAfter(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("retryAfter must not be negative: PT-1S");
    assertThat(failure.retryAfter(Duration.ZERO).remainingAttempts(0)).isSameAs(failure);
  }

  private static Bikesched node(String name) {
    return testNode(name).build();
  }
}
