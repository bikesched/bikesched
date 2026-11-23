// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.admin.JobNameSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Scheduling jobs of names other nodes handle, declared with {@code
 * BikeschedBuilder.remoteJobNames(…)}.
 */
class RemoteJobNamesTest extends AbstractBikeschedTest {

  /** Deletes the jobs left behind (future jobs no node runs). */
  @AfterEach
  void deleteJobs() throws Exception {
    execute("delete from bikesched_jobs where name like 'remote-%'");
  }

  @Test
  void aRemoteJobNameIsScheduledWithoutAHandlerAndRunsOnTheNodeHandlingIt() throws Exception {
    String name = "remote-basic";
    var ranOn = new CompletableFuture<String>();
    try (Bikesched scheduler =
            testNode("node-remote-scheduler").remoteJobNames(name, "remote-other").build();
        Bikesched worker = testNode("node-remote-worker").build()) {
      UUID jobId = scheduler.newJob(name).payload("{\"x\": 1}").schedule();
      // The scheduling node polls too, but does not claim the job: it has no handler for it.
      Thread.sleep(500);
      assertThat(count("id = '" + jobId + "' and state = 'SCHEDULED'")).isOne();

      worker.registerJobHandler(name, job -> ranOn.complete(worker.node().name()));

      assertThat(ranOn.get(10, TimeUnit.SECONDS)).isEqualTo("node-remote-worker");
      awaitState(jobId, "SUCCEEDED");
    }
  }

  @Test
  void transactionalAndIdempotentJobsOfARemoteJobNameCanBeScheduled() throws Exception {
    String name = "remote-variants";
    try (Bikesched scheduler = testNode("node-remote-variants").remoteJobNames(name).build();
        Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      UUID transactional =
          scheduler.newJob(name).connection(connection).schedule(Duration.ofHours(1));
      connection.commit();
      UUID keyed = scheduler.newJob(name).idempotencyKey("k").schedule(Duration.ofHours(1));

      assertThat(scheduler.newJob(name).idempotencyKey("k").schedule()).isEqualTo(keyed);
      assertThat(count("name = '" + name + "' and state = 'SCHEDULED'")).isEqualTo(2);
      assertThat(scheduler.findJob(transactional)).isPresent();
    }
  }

  @Test
  void aChildOfARemoteJobNameRunsOnTheNodeHandlingIt() throws Exception {
    String child = "remote-child";
    var result = new CompletableFuture<String>();
    try (Bikesched parentNode = testNode("node-remote-parent").remoteJobNames(child).build();
        Bikesched childNode = testNode("node-remote-child").build()) {
      childNode.registerJobHandler(child, job -> job.complete("{\"from\": \"child\"}"));
      UUID parentId =
          parentNode
              .newJob("remote-parent")
              .handler(
                  job -> {
                    UUID childId = job.newJob(child).schedule();
                    JobResult<String> outcome = job.waitForJob(childId, String.class);
                    result.complete(outcome.orElseThrow());
                  })
              .schedule();

      assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo("{\"from\": \"child\"}");
      awaitState(parentId, "SUCCEEDED");
    }
  }

  @Test
  void aRecurringJobOfARemoteJobNameNeedsAHandler() {
    String name = "remote-recurring";
    try (Bikesched scheduler = testNode("node-remote-recurring").remoteJobNames(name).build()) {
      assertThatThrownBy(
              () -> scheduler.newJob(name).schedule(Recurrence.every(Duration.ofHours(1))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "Job name 'remote-recurring' has no handler registered: a remote job name can only"
                  + " be scheduled as a one-off or child job, a recurring job needs its handler on"
                  + " the registering node");
    }
  }

  @Test
  void anUnknownJobNameFailsAndTheMessageListsTheRemoteJobNames() {
    try (Bikesched scheduler =
        testNode("node-remote-unknown").remoteJobNames("remote-b", "remote-a").build()) {
      scheduler.registerJobHandler("remote-known", () -> {});

      assertThatThrownBy(() -> scheduler.newJob("remote-typo").schedule())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "Unknown job name 'remote-typo': no handler registered (known names: [remote-known],"
                  + " remote job names: [remote-a, remote-b])");
    }
  }

  @Test
  void remoteJobNamesAreValidated() {
    assertThatThrownBy(() -> testNode("node-remote-invalid").remoteJobNames("ok", " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("remote job name");
    assertThatThrownBy(
            () -> testNode("node-remote-invalid").remoteJobNames("x".repeat(201)).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theAdminListsRemoteJobNamesAsSchedulable() {
    String name = "remote-admin";
    try (Bikesched scheduler = testNode("node-remote-admin").remoteJobNames(name).build()) {
      assertThat(scheduler.admin().findJobNames())
          .filteredOn(summary -> summary.name().equals(name))
          .extracting(JobNameSummary::name, JobNameSummary::total, JobNameSummary::handled)
          .containsExactly(tuple(name, 0, true));
    }
  }
}
