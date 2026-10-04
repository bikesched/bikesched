package org.bikesched;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.bikesched.internal.job.JobProcessor;
import org.bikesched.job.Job;
import org.junit.jupiter.api.Test;

/** DEBUG logs of processed jobs (with their outcome) and of step invocations. */
class ProcessingLogTest extends AbstractBikeschedTest {

  @Test
  void processedJobIsLoggedWithItsOutcome() throws Exception {
    var logs = LogCapture.of(JobProcessor.class);
    UUID succeeded;
    UUID rescheduled;
    UUID failed;
    UUID cancelled;
    try (logs;
        Bikesched bikesched = node("node-outcome")) {
      succeeded = bikesched.newJob("outcome-ok").handler(job -> {}).schedule();
      rescheduled =
          bikesched
              .newJob("outcome-retry")
              .handler(job -> job.fail("later").retryAfter(Duration.ofMinutes(10)))
              .schedule();
      failed =
          bikesched
              .newJob("outcome-fail")
              .maxAttempts(1)
              .handler(
                  job -> {
                    throw new IllegalStateException("boom");
                  })
              .schedule();
      cancelled = bikesched.newJob("outcome-cancel").handler(job -> job.cancel()).schedule();

      awaitState(succeeded, "SUCCEEDED");
      awaitState(failed, "FAILED");
      awaitState(cancelled, "CANCELLED");
      awaitLog(logs, rescheduled);
    }

    assertThat(processed(logs, succeeded))
        .matches("Processed job .* of type outcome-ok \\(attempt 1\\) in \\d+ ms: SUCCEEDED");
    assertThat(processed(logs, rescheduled))
        .matches(
            "Processed job .* of type outcome-retry \\(attempt 1\\) in \\d+ ms:"
                + " RESCHEDULED for attempt 2 of 3 at \\d{4}-.*Z");
    assertThat(processed(logs, failed)).endsWith(": FAILED");
    assertThat(processed(logs, cancelled)).endsWith(": CANCELLED");
  }

  @Test
  void retryAsSoonAsPossibleIsLogged() throws Exception {
    var logs = LogCapture.of(JobProcessor.class);
    UUID jobId;
    try (logs;
        Bikesched bikesched = node("node-outcome-asap")) {
      jobId =
          bikesched
              .newJob("outcome-asap")
              .handler(
                  job -> {
                    if (job.attempts() == 1) {
                      job.fail();
                    }
                  })
              .schedule();
      awaitState(jobId, "SUCCEEDED");
    }
    assertThat(
            logs.events().stream()
                .filter(e -> e.getLevel() == Level.DEBUG)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("Processed job " + jobId)))
        .hasSize(2)
        .anyMatch(m -> m.endsWith(": RESCHEDULED for attempt 2 of 3 as soon as possible"))
        .anyMatch(m -> m.endsWith(": SUCCEEDED"));
  }

  @Test
  void stepInvocationsAreLogged() throws Exception {
    var logs = LogCapture.of(Job.class);
    UUID jobId;
    try (logs;
        Bikesched bikesched = node("node-step-log")) {
      jobId =
          bikesched
              .newJob("step-log")
              .handler(
                  job -> {
                    job.step("charge", String.class, step -> "{}");
                    job.step(
                        "mail",
                        String.class,
                        step -> {
                          if (job.attempts() == 1) {
                            throw new IllegalStateException("mail down");
                          }
                          return null;
                        });
                  })
              .schedule();
      awaitState(jobId, "SUCCEEDED");
    }
    List<String> stepLogs =
        logs.events().stream()
            .filter(e -> e.getLevel() == Level.DEBUG)
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.contains("tep ") && m.contains(jobId.toString()))
            .map(m -> m.replace(jobId.toString(), "J").replaceAll("\\d+ ms", "N ms"))
            .toList();
    assertThat(stepLogs)
        .containsExactly(
            "Executing step charge of job J (attempt 1)",
            "Step charge of job J (attempt 1) SUCCEEDED in N ms",
            "Executing step mail of job J (attempt 1)",
            "Step mail of job J (attempt 1) FAILED in N ms:"
                + " java.lang.IllegalStateException: mail down",
            "Step charge of job J already completed, reusing its result",
            "Executing step mail of job J (attempt 2)",
            "Step mail of job J (attempt 2) SUCCEEDED in N ms");
  }

  private static String processed(LogCapture logs, UUID jobId) {
    return logs.events().stream()
        .filter(e -> e.getLevel() == Level.DEBUG)
        .map(ILoggingEvent::getFormattedMessage)
        .filter(m -> m.startsWith("Processed job " + jobId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no processed log for job " + jobId));
  }

  private static void awaitLog(LogCapture logs, UUID jobId) {
    await()
        .untilAsserted(
            () ->
                assertThat(logs.messages())
                    .as("processed log of job " + jobId)
                    .anyMatch(m -> m.startsWith("Processed job " + jobId)));
  }

  private static Bikesched node(String name) {
    return Bikesched.builder()
        .dataSource(dataSource)
        .nodeName(name)
        .pollInterval(Duration.ofMillis(50))
        .build();
  }
}
