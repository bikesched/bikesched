// SPDX-License-Identifier: Apache-2.0
package org.bikesched.stress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.job.JobHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The network between one node and the database hangs: packets are neither delivered nor rejected,
 * so statements on open connections neither answer nor fail. Another node takes over the jobs of
 * the cut-off node, and the cut-off node works again once its network does.
 */
class HangingConnectionTest {

  private static StressDatabase database;

  private final Executions executions = new Executions();

  @BeforeAll
  static void startDatabase() {
    database = new StressDatabase();
  }

  @AfterAll
  static void stopDatabase() {
    database.close();
  }

  /** A network partition: every connection of the node hangs, also new ones, until it heals. */
  @Test
  void aNodeCutOffByAPartitionIsReplacedAndWorksAgainAfterwards() throws Exception {
    TcpProxy network = database.newProxy();
    DataSource pool = database.pool("partitioned", 12, network, null);
    String type = "partition-" + UUID.randomUUID();
    String soloType = "solo-" + UUID.randomUUID();
    try (Bikesched cutOff = node(pool, "partitioned", type, soloType);
        Bikesched other = otherNode(type)) {
      UUID jobId = cutOff.newJob(type).schedule();
      awaitEffect(jobId, "two-started");
      network.hang();
      other.start();

      await("the other node ran the job")
          .atMost(Duration.ofSeconds(60))
          .until(() -> database.state(jobId).equals("SUCCEEDED"));
      // Long enough for the other node to remove the cut-off node, too.
      Thread.sleep(StressNodes.NODE_TIMEOUT.multipliedBy(2).plusSeconds(2).toMillis());
      network.heal();

      assertRunsJobsAgain(soloType);
      assertThat(executions.of(jobId)).startsWith("one", "two-started").contains("two-ended");
      assertThat(executions.of(jobId, "one")).as("the completed step was not repeated").isOne();
    }
  }

  /**
   * A failover without a TCP reset: the connections open at that moment hang for good, new ones
   * work. The driver's {@code socketTimeout} ends the statements waiting on them. The node's {@code
   * statementTimeout} alone does not: the driver sends the cancel on a new connection, but the
   * answer would arrive on the hanging one, so the poller would wait forever.
   */
  @Test
  void aNodeWithASocketTimeoutRecoversFromConnectionsThatHangForGood() throws Exception {
    TcpProxy network = database.newProxy();
    DataSource pool = database.pool("frozen-socket-timeout", 12, network, 5);
    assertRecoversFromFrozenConnections(network, pool, "frozen-socket-timeout");
  }

  private void assertRecoversFromFrozenConnections(TcpProxy network, DataSource pool, String name)
      throws Exception {
    String type = name + "-" + UUID.randomUUID();
    String soloType = "solo-" + UUID.randomUUID();
    try (Bikesched frozen = node(pool, name, type, soloType);
        Bikesched other = otherNode(type)) {
      List<UUID> jobIds = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        jobIds.add(frozen.newJob(type).maxAttempts(100).schedule());
      }
      for (UUID jobId : jobIds) {
        awaitEffect(jobId, "two-started");
      }
      network.freeze();
      other.start();

      for (UUID jobId : jobIds) {
        await("job " + jobId + " succeeded")
            .atMost(Duration.ofSeconds(60))
            .until(() -> database.state(jobId).equals("SUCCEEDED"));
      }
      assertRunsJobsAgain(soloType);
    }
  }

  /**
   * A node with a statement timeout of 2 seconds whose job of the type takes 2 seconds in step two;
   * it alone handles the solo type.
   */
  private Bikesched node(DataSource pool, String name, String type, String soloType) {
    Bikesched node =
        StressNodes.node(pool, name)
            .statementTimeout(Duration.ofSeconds(2))
            .autoStartPolling(false)
            .build();
    node.registerJobHandler(type, handler(Duration.ofSeconds(2)));
    node.registerJobHandler(soloType, job -> {});
    node.start();
    return node;
  }

  /** A node with a working network for the same job type, started by the test. */
  private Bikesched otherNode(String type) {
    Bikesched node =
        StressNodes.node(database.dataSource(), "other").autoStartPolling(false).build();
    node.registerJobHandler(type, handler(Duration.ZERO));
    return node;
  }

  /**
   * A job with two steps: {@code one} records its run, {@code two} records its start, takes the
   * given time and records its end.
   */
  private JobHandler handler(Duration stepTwo) {
    return job -> {
      job.step("one", () -> executions.record(job.id(), "one"));
      job.step(
          "two",
          () -> {
            executions.record(job.id(), "two-started");
            try {
              Thread.sleep(stepTwo.toMillis());
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(e);
            }
            executions.record(job.id(), "two-ended");
          });
    };
  }

  /** A job of a type only the cut-off node handles succeeds. */
  private static void assertRunsJobsAgain(String soloType) throws Exception {
    UUID soloJob;
    try (Bikesched client =
        StressNodes.node(database.dataSource(), "client").autoStartPolling(false).build()) {
      soloJob = client.newJob(soloType).handler(job -> {}).schedule(); // never runs: not polling
    }
    await("the cut-off node runs jobs again")
        .atMost(Duration.ofSeconds(60))
        .until(() -> database.state(soloJob).equals("SUCCEEDED"));
  }

  private void awaitEffect(UUID jobId, String step) {
    await(step + " of job " + jobId)
        .atMost(Duration.ofSeconds(30))
        .until(() -> executions.of(jobId).contains(step));
  }
}
