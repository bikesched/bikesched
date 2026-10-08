// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Duration;
import org.bikesched.internal.job.claim.JobPoller;
import org.bikesched.job.JobBuilder;
import org.junit.jupiter.api.Test;

/** Limiting the number of jobs a node executes concurrently. */
class ConcurrencyTest extends AbstractBikeschedTest {

  @Test
  void neverRunsMoreThanMaxConcurrentJobs() throws Exception {
    assertThat(runSlowJobs("slow-default", 30, null))
        .isEqualTo(BikeschedBuilder.DEFAULT_MAX_CONCURRENT_JOBS);
  }

  @Test
  void claimsOnlyAsManyJobsAsThereAreFreeSlots() throws Exception {
    var logs = LogCapture.of(JobPoller.class);
    try (logs) {
      assertThat(runSlowJobs("slow-limited", 12, 3)).isEqualTo(3);
    }
    var claimed =
        java.util.regex.Pattern.compile(
            "bikesched node node-slow-limited \\(.*\\) claimed (\\d+) job\\(s\\)");
    assertThat(
            logs.events().stream()
                .map(ILoggingEvent::getFormattedMessage)
                .map(claimed::matcher)
                .filter(java.util.regex.Matcher::matches)
                .map(m -> Integer.parseInt(m.group(1))))
        .as("claim sizes never exceed the free slots")
        .isNotEmpty()
        .allSatisfy(size -> assertThat(size).isBetween(1, 3));
    var polling =
        java.util.regex.Pattern.compile(
            "bikesched node node-slow-limited \\(.*\\) polling for up to (\\d+) job\\(s\\), (\\d+) free slot\\(s\\)");
    assertThat(
            logs.events().stream()
                .map(ILoggingEvent::getFormattedMessage)
                .map(polling::matcher)
                .filter(java.util.regex.Matcher::matches))
        .as("polls try to claim min(free slots, batch size)")
        .isNotEmpty()
        .allSatisfy(
            m -> {
              assertThat(Integer.parseInt(m.group(1))).isBetween(1, 3);
              assertThat(m.group(1)).isEqualTo(m.group(2));
            });
    assertThat(logs.events())
        .extracting(ILoggingEvent::getFormattedMessage)
        .as("polls are skipped while all slots are busy")
        .anyMatch(
            m ->
                m.matches(
                    "bikesched node node-slow-limited \\(.*\\) is at capacity, skipping poll"));
  }

  @Test
  void immediatelyPollsAgainAfterFillingABatch() throws Exception {
    String jobType = "full-batch-" + java.util.UUID.randomUUID();
    var completed = new java.util.concurrent.atomic.AtomicInteger();
    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(dataSource)
            .nodeName("node-" + jobType)
            .pollInterval(Duration.ofSeconds(1))
            .maxConcurrentJobs(20)
            .autoStartPolling(false)
            .build()) {
      bikesched.registerJobHandler(jobType, _ -> completed.incrementAndGet());
      for (int i = 0; i < 20; i++) {
        bikesched.newJob(jobType).schedule();
      }

      bikesched.start();

      await()
          .atMost(Duration.ofMillis(1500))
          .untilAsserted(() -> assertThat(completed).hasValue(20));
    }
  }

  private static int runSlowJobs(String jobType, int count, Integer maxConcurrentJobs)
      throws Exception {
    var running = new java.util.concurrent.atomic.AtomicInteger();
    var peak = new java.util.concurrent.atomic.AtomicInteger();
    var builder =
        Bikesched.builder()
            .dataSource(dataSource)
            .nodeName("node-" + jobType)
            .pollInterval(Duration.ofMillis(20))
            // Polling starts once all jobs are scheduled: with a full queue the node fills its
            // slots right away, independent of how fast the jobs can be scheduled.
            .autoStartPolling(false);
    if (maxConcurrentJobs != null) {
      builder.maxConcurrentJobs(maxConcurrentJobs);
    }
    try (Bikesched bikesched = builder.build()) {
      bikesched.registerJobHandler(
          jobType,
          () -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
              Thread.sleep(200);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              running.decrementAndGet();
            }
          });
      JobBuilder<Object> slowJob = bikesched.newJob(jobType);
      for (int i = 0; i < count; i++) {
        slowJob.schedule();
      }
      bikesched.start();
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  assertThat(count("type = '" + jobType + "' and state = 'SUCCEEDED'"))
                      .isEqualTo(count));
    }
    return peak.get();
  }
}
