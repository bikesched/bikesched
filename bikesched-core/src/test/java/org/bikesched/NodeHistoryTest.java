// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.bikesched.HistoryTesting.actions;
import static org.bikesched.HistoryTesting.data;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.bikesched.history.ActorType;
import org.bikesched.history.HistoryEntry;
import org.bikesched.history.JobAction;
import org.bikesched.history.NodeAction;
import org.bikesched.history.NodeEntry;
import org.junit.jupiter.api.Test;

/**
 * The history of nodes: a node records registering and stopping itself, the node clean-up the nodes
 * it removes and, caused by the dead node, the jobs it released; {@code Admin.findNodeHistory}
 * reads them.
 */
class NodeHistoryTest extends AbstractBikeschedTest {

  @Test
  void aNodeRecordsRegisteringAndStopping() {
    UUID id;
    Bikesched reader = testNode("node-history-reader").autoStartPolling(false).build();
    try (reader) {
      try (Bikesched bikesched = node("node-history-self").build()) {
        id = bikesched.node().id();
      }

      List<HistoryEntry> history = reader.admin().findNodeHistory(id);
      assertThat(actions(history))
          .containsExactly("node-history-self REGISTERED", "node-history-self STOPPED");
      assertThat(history)
          .allSatisfy(
              entry -> {
                assertThat(entry).isInstanceOf(NodeEntry.class);
                assertThat(entry.subjectId()).isEqualTo(id);
                assertThat(entry.nodeId()).as("written by itself").isEqualTo(id);
                assertThat(entry.actorType()).isEqualTo(ActorType.NODE);
              });
    }
  }

  @Test
  void aRemovedNodeRegisteringAgainIsRecorded() throws Exception {
    try (Bikesched bikesched =
        node("node-history-again").heartbeatInterval(Duration.ofMillis(50)).build()) {
      UUID id = bikesched.node().id();
      execute("delete from bikesched_nodes where id = '" + id + "'");

      await()
          .untilAsserted(
              () ->
                  assertThat(actions(bikesched.admin().findNodeHistory(id)))
                      .containsExactly(
                          "node-history-again REGISTERED", "node-history-again REGISTERED"));
    }
  }

  @Test
  void theCleanUpRecordsRemovingADeadNodeAndReleasingItsJobs() throws Exception {
    UUID deadNode = UUID.randomUUID();
    String jobName = "history-orphan-" + UUID.randomUUID();
    try (Bikesched bikesched =
        node("node-history-reaper")
            .heartbeatInterval(Duration.ofMillis(50))
            .nodeTimeout(Duration.ofSeconds(1))
            .nodeCleanUpInterval(Duration.ofMillis(50))
            .build()) {
      bikesched.registerJobHandler(jobName, () -> {});
      execute(
          "insert into bikesched_nodes (id, name, last_heartbeat_at) values ('"
              + deadNode
              + "', 'node-history-dead', now() - interval '1 minute')");
      execute(
          "insert into bikesched_jobs (id, name, state, attempts, locked_by_node_id, lock_token,"
              + " locked_at) values (gen_random_uuid(), '"
              + jobName
              + "', 'RUNNING', 1, '"
              + deadNode
              + "', gen_random_uuid(), now())");

      await()
          .untilAsserted(
              () ->
                  assertThat(actions(bikesched.admin().findNodeHistory(deadNode)))
                      .containsExactly("RELEASED", "node-history-dead REMOVED"));

      List<HistoryEntry> history = bikesched.admin().findNodeHistory(deadNode);
      HistoryEntry released = history.getFirst();
      assertThat(released.action()).isEqualTo(JobAction.RELEASED);
      assertThat(released.causedById()).isEqualTo(deadNode);
      assertThat(data(released, "reason")).isEqualTo("NODE_DEAD");
      HistoryEntry removed = history.getLast();
      assertThat(removed.action()).isEqualTo(NodeAction.REMOVED);
      assertThat(removed.nodeId())
          .as("written by the removing node")
          .isEqualTo(bikesched.node().id());
    } finally {
      execute("delete from bikesched_jobs where name = '" + jobName + "'");
    }
  }

  @Test
  void nothingIsRecordedWithoutTheHistory() {
    UUID id;
    try (Bikesched bikesched = testNode("node-history-off").build()) {
      id = bikesched.node().id();
    }
    try (Bikesched reader = testNode("node-history-off-reader").autoStartPolling(false).build()) {
      assertThat(reader.admin().findNodeHistory(id)).isEmpty();
    }
  }

  /** A polling node recording the history. */
  private static BikeschedBuilder node(String name) {
    return testNode(name).history(Duration.ofDays(1));
  }
}
