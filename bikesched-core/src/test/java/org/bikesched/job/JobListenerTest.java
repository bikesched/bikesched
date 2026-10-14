// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.LogCapture;
import org.bikesched.internal.job.JobProcessor;
import org.bikesched.job.listener.JobAttemptResult;
import org.bikesched.job.listener.JobClaim;
import org.bikesched.job.listener.JobListener;
import org.junit.jupiter.api.Test;

/** The {@link JobListener} sees every claim and the recorded outcome of each attempt. */
class JobListenerTest extends AbstractBikeschedTest {

  /** Records the calls as strings, e.g. {@code "claimed 1"}, {@code "ended 1 SUCCEEDED"}. */
  static final class RecordingListener implements JobListener {
    final List<JobClaim> claims = new CopyOnWriteArrayList<>();
    final List<JobAttemptResult> results = new CopyOnWriteArrayList<>();
    final List<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public void onJobClaimed(JobClaim claim) {
      claims.add(claim);
      calls.add((claim.compensation() ? "claimed compensation " : "claimed ") + claim.attempt());
    }

    @Override
    public void onAttemptEnded(JobAttemptResult result) {
      results.add(result);
      calls.add(
          "ended "
              + result.claim().attempt()
              + " "
              + result.outcome()
              + (result.isAbandoned() ? " abandoned" : ""));
    }

    @Override
    public void onAbandonedAttemptReturned(JobClaim claim) {
      calls.add("returned " + claim.attempt());
    }
  }

  @Test
  void reportsSuccessWithTheClaimDelay() {
    var listener = new RecordingListener();
    String type = unique("listener-success");
    try (Bikesched bikesched = testNode("node-listener-success").jobListener(listener).build()) {
      UUID jobId =
          bikesched
              .newJob(type)
              .handler(_ -> {})
              .schedule(Instant.now().minus(Duration.ofSeconds(5)));

      awaitState(jobId, "SUCCEEDED");
      await()
          .untilAsserted(
              () -> assertThat(listener.calls).containsExactly("claimed 1", "ended 1 SUCCEEDED"));
      JobClaim claim = listener.claims.getFirst();
      assertThat(claim.jobId()).isEqualTo(jobId);
      assertThat(claim.jobType()).isEqualTo(type);
      assertThat(claim.delay()).isGreaterThanOrEqualTo(Duration.ofSeconds(5));
      assertThat(listener.results.getFirst().duration()).isPositive();
    }
  }

  @Test
  void reportsRetriesAndThePermanentFailure() {
    var listener = new RecordingListener();
    try (Bikesched bikesched = testNode("node-listener-failure").jobListener(listener).build()) {
      UUID jobId =
          bikesched
              .newJob(unique("listener-failure"))
              .maxAttempts(2)
              .handler(
                  _ -> {
                    throw new IllegalStateException("broken");
                  })
              .schedule();

      awaitState(jobId, "FAILED");
      await()
          .untilAsserted(
              () ->
                  assertThat(listener.calls)
                      .containsExactly(
                          "claimed 1", "ended 1 RETRYING", "claimed 2", "ended 2 FAILED"));
    }
  }

  @Test
  void reportsCancellationAndCompensation() {
    var listener = new RecordingListener();
    try (Bikesched bikesched =
        testNode("node-listener-compensation").jobListener(listener).build()) {
      UUID jobId =
          bikesched
              .newJob(unique("listener-compensation"))
              .handler(
                  job -> {
                    job.newStep("a", String.class).compensateWith((_, _) -> {}).run(_ -> "1");
                    if (!job.isCompensating()) {
                      job.cancel("no longer needed");
                    }
                  })
              .schedule();

      awaitState(jobId, "CANCELLED");
      // The compensation's claim can be reported before the attempt's end.
      await()
          .untilAsserted(
              () -> {
                assertThat(listener.calls.stream().filter(c -> c.startsWith("claimed ")))
                    .containsExactly("claimed 1", "claimed compensation 1");
                assertThat(listener.calls.stream().filter(c -> c.startsWith("ended ")))
                    .containsExactly("ended 1 COMPENSATING", "ended 1 COMPENSATED");
              });
    }
  }

  @Test
  void reportsWaitingForAnEvent() {
    var listener = new RecordingListener();
    String eventName = unique("listener-event");
    try (Bikesched bikesched = testNode("node-listener-event").jobListener(listener).build()) {
      UUID jobId =
          bikesched
              .newJob(unique("listener-event"))
              .handler(job -> job.waitForEvent("wait", eventName, "k", String.class).receive())
              .schedule();
      awaitState(jobId, "WAITING");

      bikesched.newEvent(eventName, "k").payload("\"paid\"").publish();

      awaitState(jobId, "SUCCEEDED");
      await()
          .untilAsserted(
              () ->
                  assertThat(listener.calls)
                      .containsExactly(
                          "claimed 1", "ended 1 WAITING", "claimed 1", "ended 1 SUCCEEDED"));
    }
  }

  @Test
  void reportsAbandonedAttemptsAndTheirReturn() {
    var listener = new RecordingListener();
    var release = new AtomicBoolean();
    try (Bikesched bikesched =
        testNode("node-listener-abandoned")
            .hardTimeoutGrace(Duration.ofMillis(200))
            .jobListener(listener)
            .build()) {
      UUID jobId =
          bikesched
              .newJob(unique("listener-abandoned"))
              .maxAttempts(1)
              .timeout(Duration.ofMillis(100))
              .handler(
                  _ -> {
                    while (!release.get()) {
                      try {
                        Thread.sleep(10);
                      } catch (InterruptedException ignored) {
                        // ignored on purpose: the handler does not react to its timeout
                      }
                    }
                  })
              .schedule();

      awaitState(jobId, "FAILED");
      await()
          .untilAsserted(
              () ->
                  assertThat(listener.calls)
                      .containsExactly("claimed 1", "ended 1 FAILED abandoned"));

      release.set(true);
      await()
          .untilAsserted(
              () ->
                  assertThat(listener.calls)
                      .containsExactly("claimed 1", "ended 1 FAILED abandoned", "returned 1"));
    }
  }

  @Test
  void failingListenerDoesNotAffectTheJob() throws Exception {
    var logs = LogCapture.of(JobProcessor.class);
    var listener =
        new JobListener() {
          @Override
          public void onJobClaimed(JobClaim claim) {
            throw new IllegalStateException("listener broken");
          }
        };
    try (logs;
        Bikesched bikesched = testNode("node-listener-broken").jobListener(listener).build()) {
      UUID jobId = bikesched.newJob(unique("listener-broken")).handler(_ -> {}).schedule();

      awaitState(jobId, "SUCCEEDED");
      assertThat(logs.messages())
          .contains("Job listener failed for job " + jobId + ", ignoring it");
    }
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
