// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Duration;
import java.util.UUID;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.BikeschedLogContext;
import org.bikesched.LogCapture;
import org.bikesched.internal.DefaultBikesched;
import org.bikesched.internal.job.JobProcessor;
import org.bikesched.internal.job.claim.JobPoller;
import org.junit.jupiter.api.Test;

/** Executing jobs: polling, handlers, retries, cancellation and the logging context. */
class JobExecutionTest extends AbstractBikeschedTest {

  @Test
  void pollerClaimsJobsInBatchesAndRunsHandlersOnVirtualThreads() throws Exception {
    var logs = LogCapture.of(DefaultBikesched.class, JobPoller.class, JobProcessor.class);
    var handled = new java.util.concurrent.ConcurrentLinkedQueue<String>();
    try (logs;
        Bikesched bikesched = testNode("node-worker").build()) {
      bikesched.registerJobHandler(
          "print-batch",
          job ->
              handled.add(
                  (Thread.currentThread().isVirtual() ? "virtual " : "platform ")
                      + Thread.currentThread().getName()
                      + " "
                      + job.name()
                      + " "
                      + job.payload()));
      execute(
          "insert into bikesched_jobs (id, name, payload) "
              + "select gen_random_uuid(), 'print-batch', jsonb_build_object('n', n) from generate_series(1, 25) n");

      await()
          .untilAsserted(
              () ->
                  assertThat(count("name = 'print-batch' and state = 'SUCCEEDED'")).isEqualTo(25));
    }

    assertThat(handled)
        .hasSize(25)
        .allSatisfy(
            entry ->
                assertThat(entry)
                    .matches("virtual bikesched-job-\\d+ print-batch \\{\"n\": \\d+}"));
    assertThat(logs.events())
        .filteredOn(
            e ->
                e.getLevel() == ch.qos.logback.classic.Level.DEBUG
                    && e.getFormattedMessage()
                        .matches(
                            "Executing job .* named print-batch \\(attempt 1 of 3\\) "
                                + "on bikesched node node-worker \\(.*\\)"))
        .as("debug log before each job execution, on the job's virtual thread")
        .hasSize(25)
        .allSatisfy(e -> assertThat(e.getThreadName()).startsWith("bikesched-job-"));
    var claimed =
        java.util.regex.Pattern.compile(
            "bikesched node node-worker \\([0-9a-f-]{36}\\) claimed (\\d+) job\\(s\\)");
    assertThat(
            logs.events().stream()
                .map(ILoggingEvent::getFormattedMessage)
                .map(claimed::matcher)
                .filter(java.util.regex.Matcher::matches)
                .map(m -> Integer.parseInt(m.group(1))))
        .as("poll batch sizes")
        .allSatisfy(size -> assertThat(size).isBetween(1, 10))
        .hasSizeGreaterThanOrEqualTo(3);
  }

  @Test
  void runnableHandlerIsSupported() throws Exception {
    try (Bikesched bikesched = testNode("node-runnable").build()) {
      var ran = new java.util.concurrent.CountDownLatch(1);
      bikesched.registerJobHandler("ping", ran::countDown);
      bikesched.newJob("ping").schedule();

      assertThat(ran.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void jobWithoutHandlerIsLeftToNodesThatHaveOne() throws Exception {
    // E.g. during a rolling deploy: a job of a type only the new version handles, scheduled by a
    // node of the new version while this node (of the old version) still polls.
    String type = "handled-elsewhere-" + UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    try {
      try (Bikesched old = testNode("node-old-version").build()) {
        old.registerJobHandler("handled-here", () -> {});
        execute("insert into bikesched_jobs (id, name) values ('" + jobId + "', '" + type + "')");
        Thread.sleep(500); // several polls
        assertThat(count("id = '" + jobId + "' and state = 'SCHEDULED' and attempts = 0"))
            .as("not claimed without a handler")
            .isOne();
      }
      try (Bikesched updated = testNode("node-new-version").build()) {
        updated.registerJobHandler(type, () -> {});
        awaitState(jobId, "SUCCEEDED");
        assertThat(count("id = '" + jobId + "' and attempts = 1")).isOne();
      }
    } finally {
      execute("delete from bikesched_jobs where name = '" + type + "'");
    }
  }

  @Test
  void failingHandlerIsRetriedUntilMaxAttemptsThenMarkedFailed() throws Exception {
    try (Bikesched bikesched = testNode("node-failing").build()) {
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      bikesched.registerJobHandler(
          "explode",
          job -> {
            calls.incrementAndGet();
            throw new java.io.IOException("attempt " + job.attempts() + " exploded");
          });
      UUID jobId = bikesched.newJob("explode").schedule();

      awaitState(jobId, "FAILED");
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and state = 'FAILED' and attempts = 3 and locked_by_node_id is null "
                      + "and last_error = 'java.io.IOException: attempt 3 exploded'"))
          .isEqualTo(1);
      assertThat(calls).hasValue(3);
    }
  }

  @Test
  void handlerCanCancelJobWithMessageAndItIsNotRetried() throws Exception {
    var logs = LogCapture.of(JobProcessor.class);
    try (logs;
        Bikesched bikesched = testNode("node-cancel").build()) {
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      var continuedAfterCancel = new java.util.concurrent.atomic.AtomicBoolean();
      UUID jobId =
          bikesched
              .newJob("cancel-me")
              .maxAttempts(5)
              .handler(
                  job -> {
                    if (calls.incrementAndGet() == 1) {
                      throw new IllegalStateException("first attempt fails");
                    }
                    job.cancel("customer deleted");
                    continuedAfterCancel.set(true); // cancel() does not stop the handler
                  })
              .schedule();

      awaitState(jobId, "CANCELLED");
      Thread.sleep(300); // several poll cycles: a cancelled job is not picked up again

      assertThat(calls).hasValue(2);
      assertThat(continuedAfterCancel).isTrue();
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and state = 'CANCELLED' and attempts = 2 and locked_by_node_id is null "
                      + "and locked_at is null and finished_at is not null and last_error = 'customer deleted'"))
          .isEqualTo(1);
    }
    assertThat(logs.events())
        .filteredOn(
            e ->
                e.getLevel() == ch.qos.logback.classic.Level.INFO
                    && e.getFormattedMessage()
                        .matches(
                            "Job .* named cancel-me cancelled by its handler "
                                + "\\(attempt 2 of 5\\): customer deleted"))
        .hasSize(1);
  }

  @Test
  void handlerCanCancelJobWithoutMessage() throws Exception {
    try (Bikesched bikesched = testNode("node-cancel-silent").build()) {
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      UUID jobId =
          bikesched
              .newJob("cancel-silent")
              .handler(
                  job -> {
                    if (calls.incrementAndGet() == 1) {
                      throw new IllegalStateException("first attempt fails");
                    }
                    job.cancel();
                  })
              .schedule();

      awaitState(jobId, "CANCELLED");

      assertThat(count("id = '" + jobId + "' and state = 'CANCELLED' and last_error is null"))
          .as("the earlier failure is cleared")
          .isEqualTo(1);
    }
  }

  @Test
  void cancelledJobStaysCancelledWhenHandlerThrowsAfterwards() throws Exception {
    var logs = LogCapture.of(JobProcessor.class);
    UUID jobId;
    try (logs;
        Bikesched bikesched = testNode("node-cancel-throw").build()) {
      jobId =
          bikesched
              .newJob("cancel-throw")
              .handler(
                  job -> {
                    job.cancel("nope");
                    throw new java.io.IOException("cleanup failed");
                  })
              .schedule();

      awaitState(jobId, "CANCELLED");

      assertThat(count("id = '" + jobId + "' and attempts = 1 and last_error = 'nope'"))
          .isEqualTo(1);
    }
    assertThat(logs.events())
        .filteredOn(
            e ->
                e.getLevel() == ch.qos.logback.classic.Level.WARN
                    && e.getFormattedMessage()
                        .equals(
                            "Job "
                                + jobId
                                + " named cancel-throw was cancelled, "
                                + "ignoring the exception thrown by its handler afterwards"))
        .singleElement()
        .satisfies(e -> assertThat(e.getThrowableProxy().getMessage()).isEqualTo("cleanup failed"));
  }

  @Test
  void logContextContainsNodeIdAndJobId() throws Exception {
    var logs = LogCapture.of(DefaultBikesched.class, JobPoller.class, JobProcessor.class);
    UUID nodeId;
    UUID jobId;
    var seenByHandler = new java.util.concurrent.CompletableFuture<java.util.Map<String, String>>();
    try (logs;
        Bikesched bikesched = testNode("node-mdc", Duration.ofMillis(50)).build()) {
      nodeId = bikesched.node().id();
      jobId =
          bikesched
              .newJob("mdc")
              .handler(() -> seenByHandler.complete(org.slf4j.MDC.getCopyOfContextMap()))
              .schedule();

      assertThat(seenByHandler.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .as("visible inside the job handler")
          .containsEntry(BikeschedLogContext.NODE_ID, nodeId.toString())
          .containsEntry(BikeschedLogContext.JOB_ID, jobId.toString())
          .containsEntry(BikeschedLogContext.JOB_NAME, "mdc");
    }

    assertThat(logs.events())
        .filteredOn(e -> e.getFormattedMessage().startsWith("Executing job " + jobId))
        .singleElement()
        .satisfies(
            e ->
                assertThat(e.getMDCPropertyMap())
                    .containsEntry(BikeschedLogContext.NODE_ID, nodeId.toString())
                    .containsEntry(BikeschedLogContext.JOB_ID, jobId.toString())
                    .containsEntry(BikeschedLogContext.JOB_NAME, "mdc"));
    assertThat(logs.events())
        .filteredOn(e -> e.getThreadName().equals("bikesched-poller"))
        .as("poller logs carry the node id but no job")
        .isNotEmpty()
        .allSatisfy(
            e ->
                assertThat(e.getMDCPropertyMap())
                    .containsEntry(BikeschedLogContext.NODE_ID, nodeId.toString())
                    .doesNotContainKeys(BikeschedLogContext.JOB_ID, BikeschedLogContext.JOB_NAME));
  }
}
