// SPDX-License-Identifier: Apache-2.0
package org.bikesched.stress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.Bikesched;
import org.bikesched.job.JobState;
import org.bikesched.job.listener.JobAttemptOutcome;
import org.bikesched.job.listener.JobAttemptResult;
import org.bikesched.job.listener.JobListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The database restarts or is down for a while in the middle of jobs: every job still ends, and
 * completed steps are not repeated.
 */
class DatabaseRestartTest {

  private static StressDatabase database;

  @BeforeAll
  static void startDatabase() {
    database = new StressDatabase();
  }

  @AfterAll
  static void stopDatabase() {
    database.close();
  }

  @Test
  void anOutcomeThatCouldNotBeRecordedIsRecordedByTheNextAttempt() throws Exception {
    var executions = new Executions();
    String type = "outcome-" + UUID.randomUUID();
    var handlerDone = new CountDownLatch(1);
    var databaseDown = new CountDownLatch(1);
    var attempts = new AtomicInteger();
    var notRecorded = new CountDownLatch(1);
    JobListener listener =
        new JobListener() {
          @Override
          public void onAttemptEnded(JobAttemptResult result) {
            if (result.outcome() == JobAttemptOutcome.NOT_RECORDED) {
              notRecorded.countDown();
            }
          }
        };
    try (Bikesched node =
        StressNodes.node(database.pool("outcome", 12), "outcome")
            .jobListener(listener)
            .autoStartPolling(false)
            .build()) {
      node.registerJobHandler(
          type,
          job -> {
            job.step("one", () -> executions.record(job.id(), "one"));
            if (attempts.incrementAndGet() == 1) {
              handlerDone.countDown();
              // Returns while the database is down: recording SUCCEEDED fails.
              databaseDown.await(1, TimeUnit.MINUTES);
            }
          });
      node.start();
      UUID id = node.newJob(type).schedule();

      assertThat(handlerDone.await(30, TimeUnit.SECONDS)).isTrue();
      database.stop();
      databaseDown.countDown();
      assertThat(notRecorded.await(30, TimeUnit.SECONDS))
          .as("the node gave up recording the outcome")
          .isTrue();
      database.start();

      await("job succeeded")
          .atMost(Duration.ofSeconds(60))
          .ignoreExceptions() // until the pool has reconnected
          .until(() -> node.findJob(id).orElseThrow().state() == JobState.SUCCEEDED);
      assertThat(attempts).as("the handler ran again after the outage").hasValue(2);
      assertThat(executions.of(id, "one")).as("the completed step was not repeated").isOne();
    }
  }

  @Test
  void childJobsEventWaitsSleepsAndTimeoutsSurviveARestart() throws Exception {
    var mixed = new MixedJobs();
    int jobs = 200;
    try (Bikesched a = node("restart-a", mixed);
        Bikesched b = node("restart-b", mixed)) {
      List<UUID> parents = new ArrayList<>();
      for (int i = 0; i < jobs; i++) {
        parents.add(mixed.schedule(a));
      }
      // Half of the events before the restart, so jobs are waiting, sleeping and running children
      // when the database goes down; the other half after it.
      for (UUID parent : parents.subList(0, jobs / 2)) {
        mixed.publishEvent(a, parent);
      }
      await("a third of the children succeeded")
          .atMost(Duration.ofMinutes(1))
          .until(() -> database.queryLong(mixed.countChildren("SUCCEEDED")) >= jobs / 3);

      database.restart();

      for (UUID parent : parents.subList(jobs / 2, jobs)) {
        await("event published")
            .atMost(Duration.ofSeconds(30))
            .ignoreExceptions() // until the pool has reconnected
            .until(
                () -> {
                  mixed.publishEvent(a, parent);
                  return true;
                });
      }
      await("every parent job succeeded")
          .atMost(Duration.ofMinutes(2))
          .pollInterval(Duration.ofMillis(500))
          .ignoreExceptions()
          .until(() -> database.queryLong(mixed.countParents("SUCCEEDED")) == jobs);
      assertThat(database.queryLong(mixed.countChildren("SUCCEEDED"))).isEqualTo(jobs);
      Executions executions = mixed.executions();
      assertThat(executions.jobsWith("one")).isEqualTo(jobs);
      assertThat(executions.jobsWith("two")).isEqualTo(jobs);
      assertThat(executions.jobsWith("child-attempt-2"))
          .as("children retried after their timeout")
          .isEqualTo(jobs);
      // A step runs again only if its attempt was cut off by the restart: at most one step per
      // job running on the two nodes at that moment.
      assertThat(executions.repeated())
          .as("steps repeated after the restart")
          .isLessThanOrEqualTo(2 * 10);
      assertNodesStillWork(a, b);
    }
  }

  @Test
  void nodesRecoverFromAnOutageLongerThanTheNodeTimeout() throws Exception {
    var mixed = new MixedJobs();
    int jobs = 100;
    try (Bikesched a = node("outage-a", mixed);
        Bikesched b = node("outage-b", mixed)) {
      for (int i = 0; i < jobs; i++) {
        mixed.publishEvent(a, mixed.schedule(a));
      }
      await("a third of the children succeeded")
          .atMost(Duration.ofMinutes(1))
          .until(() -> database.queryLong(mixed.countChildren("SUCCEEDED")) >= jobs / 3);

      database.stop();
      // Twice the node timeout: when the database is back, each node finds the other's heartbeat
      // older than the time after which dead nodes are removed.
      Thread.sleep(StressNodes.NODE_TIMEOUT.multipliedBy(2).plusSeconds(2).toMillis());
      database.start();

      await("every parent job succeeded")
          .atMost(Duration.ofMinutes(2))
          .pollInterval(Duration.ofMillis(500))
          .ignoreExceptions()
          .until(() -> database.queryLong(mixed.countParents("SUCCEEDED")) == jobs);
      assertThat(mixed.executions().jobsWith("two")).isEqualTo(jobs);
      assertNodesStillWork(a, b);
    }
  }

  /**
   * A node running the {@link MixedJobs} with up to 10 concurrent jobs and two connections more
   * (for its listener and housekeeping).
   */
  private static Bikesched node(String name, MixedJobs mixed) {
    Bikesched node =
        StressNodes.node(database.pool(name, 12), name)
            .maxConcurrentJobs(10)
            .autoStartPolling(false)
            .build();
    mixed.register(node);
    node.start();
    return node;
  }

  /** Both nodes are registered again and each runs a job of a type only it handles. */
  private static void assertNodesStillWork(Bikesched a, Bikesched b) {
    for (Bikesched node : List.of(a, b)) {
      String type = "still-working-" + UUID.randomUUID();
      node.registerJobHandler(type, job -> {});
      UUID id = node.newJob(type).schedule();
      await(node.node().name() + " runs jobs again")
          .atMost(Duration.ofSeconds(30))
          .ignoreExceptions()
          .until(() -> node.findJob(id).orElseThrow().state() == JobState.SUCCEEDED);
    }
  }
}
