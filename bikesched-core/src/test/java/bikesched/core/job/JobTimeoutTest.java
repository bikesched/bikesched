package bikesched.core.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bikesched.core.AbstractBikeschedTest;
import bikesched.core.Bikesched;
import bikesched.core.BikeschedBuilder;
import bikesched.core.BikeschedLogContext;
import bikesched.core.LogCapture;
import bikesched.core.internal.job.JobProcessor;
import ch.qos.logback.classic.Level;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Timeouts of job attempts: {@link JobBuilder#timeout(Duration)}, {@link
 * BikeschedBuilder#defaultJobTimeout(Duration)}.
 */
class JobTimeoutTest extends AbstractBikeschedTest {

  @Test
  void attemptExceedingItsTimeoutIsInterruptedAndRetried() throws Exception {
    var logs = LogCapture.of(JobProcessor.class);
    var calls = new AtomicInteger();
    var interrupted = new CompletableFuture<Boolean>();
    UUID jobId;
    try (logs;
        Bikesched bikesched = testNode("node-timeout").build()) {
      jobId =
          bikesched
              .newJob("hanging")
              .maxAttempts(2)
              .timeout(Duration.ofMillis(200))
              .handler(
                  job -> {
                    if (calls.incrementAndGet() == 1) {
                      try {
                        Thread.sleep(Duration.ofMinutes(5)); // hangs until interrupted
                      } catch (InterruptedException e) {
                        interrupted.complete(true);
                        throw e;
                      }
                    }
                  })
              .schedule();

      assertThat(interrupted.get(10, TimeUnit.SECONDS)).isTrue();
      awaitState(jobId, "SUCCEEDED");
      assertThat(calls).as("retried after the timeout").hasValue(2);
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and attempts = 2 "
                      + "and last_error like 'bikesched.core.BikeschedException: Job timed out after PT0.2S'"))
          .as("the timed out attempt is recorded")
          .isEqualTo(1);
    }
    assertThat(logs.events())
        .filteredOn(
            e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage()
                        .equals(
                            "Job "
                                + jobId
                                + " of type hanging exceeded its timeout of PT0.2S (attempt 1 of 2), interrupting it"))
        .singleElement()
        .satisfies(
            e ->
                assertThat(e.getMDCPropertyMap())
                    .containsEntry(BikeschedLogContext.JOB_ID, jobId.toString()));
  }

  @Test
  void timedOutLastAttemptMarksJobFailed() throws Exception {
    try (Bikesched bikesched = testNode("node-timeout-failed").build()) {
      UUID jobId =
          bikesched
              .newJob("always-hanging")
              .maxAttempts(1)
              .timeout(Duration.ofMillis(100))
              .handler(
                  () -> {
                    try {
                      Thread.sleep(Duration.ofMinutes(5));
                    } catch (InterruptedException e) {
                      // swallowed: returning normally after the timeout still counts as timed out
                    }
                  })
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and last_error = "
                      + "'bikesched.core.BikeschedException: Job timed out after PT0.1S'"))
          .isEqualTo(1);
    }
  }

  @Test
  void nodeDefaultTimeoutAppliesToJobsWithoutTimeout() throws Exception {
    try (Bikesched bikesched =
        testNode("node-default-timeout").defaultJobTimeout(Duration.ofMillis(100)).build()) {
      UUID jobId =
          bikesched
              .newJob("default-timeout")
              .maxAttempts(1)
              .handler(job -> Thread.sleep(Duration.ofMinutes(5)))
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and timeout_millis is null "
                      + "and last_error like '%Job timed out after PT0.1S'"))
          .isEqualTo(1);
    }
  }

  @Test
  void jobTimeoutOverridesNodeDefault() throws Exception {
    try (Bikesched bikesched =
        testNode("node-timeout-override").defaultJobTimeout(Duration.ofMillis(50)).build()) {
      UUID jobId =
          bikesched
              .newJob("slow-but-allowed")
              .maxAttempts(1)
              .timeout(Duration.ofSeconds(10))
              .handler(job -> Thread.sleep(300))
              .schedule();

      awaitState(jobId, "SUCCEEDED");
    }
  }

  @Test
  void jobFinishingInTimeIsNotInterrupted() throws Exception {
    try (Bikesched bikesched = testNode("node-in-time").build()) {
      var done = new CountDownLatch(1);
      UUID jobId =
          bikesched
              .newJob("in-time")
              .maxAttempts(1)
              .timeout(Duration.ofSeconds(10))
              .handler(
                  job -> {
                    Thread.sleep(50);
                    done.countDown();
                  })
              .schedule();

      assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
      awaitState(jobId, "SUCCEEDED");
      assertThat(count("id = '" + jobId + "' and last_error is null")).isEqualTo(1);
    }
  }

  @Test
  void cancellationWinsOverTimeout() throws Exception {
    try (Bikesched bikesched = testNode("node-cancel-timeout").build()) {
      UUID jobId =
          bikesched
              .newJob("cancel-then-hang")
              .timeout(Duration.ofMillis(100))
              .handler(
                  job -> {
                    job.cancel("gave up");
                    Thread.sleep(Duration.ofMinutes(5));
                  })
              .schedule();

      awaitState(jobId, "CANCELLED");

      assertThat(count("id = '" + jobId + "' and attempts = 1 and last_error = 'gave up'"))
          .isEqualTo(1);
    }
  }

  @Test
  void handlerCanExtendItsTimeoutWhileMakingProgress() throws Exception {
    try (Bikesched bikesched = testNode("node-extend-timeout").build()) {
      UUID jobId =
          bikesched
              .newJob("encode-video")
              .maxAttempts(1)
              .timeout(Duration.ofMillis(300))
              .handler(
                  job -> {
                    for (int chunk = 0;
                        chunk < 6;
                        chunk++) { // ~600 ms in total, twice the initial timeout
                      job.updateTimeout(Duration.ofMillis(300));
                      Thread.sleep(100);
                    }
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");
    }
  }

  @Test
  void handlerCanStartATimeoutForJobWithoutOne() throws Exception {
    try (Bikesched bikesched = testNode("node-start-timeout").build()) {
      UUID jobId =
          bikesched
              .newJob("poll-result")
              .maxAttempts(1)
              .handler(
                  job -> {
                    job.updateTimeout(Duration.ofMillis(100));
                    Thread.sleep(Duration.ofMinutes(5));
                  })
              .schedule();

      awaitState(jobId, "FAILED");

      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and timeout_millis is null "
                      + "and last_error = 'bikesched.core.BikeschedException: Job timed out after PT0.1S'"))
          .as("the updated timeout is reported, but not stored for retries")
          .isEqualTo(1);
    }
  }

  @Test
  void timeoutCannotBeUpdatedOnceTheAttemptEnded() throws Exception {
    try (Bikesched bikesched = testNode("node-update-ended").build()) {
      var executed = new CompletableFuture<Job>();
      UUID jobId = bikesched.newJob("ended").maxAttempts(1).handler(executed::complete).schedule();
      Job job = executed.get(10, TimeUnit.SECONDS);
      awaitState(jobId, "SUCCEEDED");

      assertThatThrownBy(() -> job.updateTimeout(Duration.ofSeconds(1)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Job " + jobId + " is no longer running");
      assertThatThrownBy(() -> job.updateTimeout(Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void timeoutsMustBePositive() {
    assertThatThrownBy(() -> Bikesched.builder().defaultJobTimeout(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("defaultJobTimeout must be positive");
    try (Bikesched bikesched = testNode("node-timeout-invalid").build()) {
      assertThatThrownBy(() -> bikesched.newJob("x").timeout(Duration.ofSeconds(-1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("timeout must be positive: PT-1S");
    }
  }
}
