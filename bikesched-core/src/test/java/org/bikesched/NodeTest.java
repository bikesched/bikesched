// SPDX-License-Identifier: Apache-2.0
package org.bikesched;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.bikesched.internal.node.NodeCleanUp;
import org.bikesched.internal.node.NodeHeartbeat;
import org.junit.jupiter.api.Test;

/**
 * Node registration: heartbeats, shared names, restarts next to stale nodes and cleaning up dead
 * nodes.
 */
class NodeTest extends AbstractBikeschedTest {

  @Test
  void heartbeatUpdatesNode() throws Exception {
    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(dataSource)
            .nodeName("node-heartbeat")
            .heartbeatInterval(Duration.ofMillis(100))
            .build()) {
      UUID id = bikesched.node().id().orElseThrow();
      OffsetDateTime registered = lastHeartbeat(id);
      await().untilAsserted(() -> assertThat(lastHeartbeat(id)).isAfter(registered));
    }
  }

  @Test
  void removedNodeRegistersAgainUnderItsId() throws Exception {
    var logs = LogCapture.of(NodeHeartbeat.class);
    try (logs;
        Bikesched bikesched = testNode("node-removed", Duration.ofMillis(50)).build()) {
      bikesched.registerJobHandler("after-removal", () -> {});
      UUID id = bikesched.node().id().orElseThrow();

      // Like other nodes do after missing its heartbeats for twice the node timeout.
      execute("delete from bikesched_nodes where id = '" + id + "'");

      await().untilAsserted(() -> assertThat(nodeName(id)).isEqualTo("node-removed"));
      UUID jobId = bikesched.newJob("after-removal").schedule();
      awaitState(jobId, "SUCCEEDED");
      assertThat(logs.messages())
          .contains(
              "bikesched node node-removed ("
                  + id
                  + ") was removed after missing heartbeats, registered it again");
    }
    execute("delete from bikesched_jobs where name = 'after-removal'");
  }

  @Test
  void removedNodeRegistersAgainNextToAnotherNodeWithItsName() throws Exception {
    UUID other = UUID.randomUUID();
    try (Bikesched bikesched = testNode("node-name-shared", Duration.ofMillis(50)).build()) {
      UUID id = bikesched.node().id().orElseThrow();

      execute(
          "begin; delete from bikesched_nodes where id = '"
              + id
              + "'; insert into bikesched_nodes (id, name) values ('"
              + other
              + "', 'node-name-shared'); commit");

      await().untilAsserted(() -> assertThat(nodeName(id)).isEqualTo("node-name-shared"));
      assertThat(nodeName(other)).isEqualTo("node-name-shared");
    }
    execute("delete from bikesched_nodes where id = '" + other + "'");
  }

  @Test
  void nodeRegistersAndHeartbeatsOnlyOncePolling() throws Exception {
    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(dataSource)
            .nodeName("node-registers-later")
            .heartbeatInterval(Duration.ofMillis(50))
            .autoStartPolling(false)
            .build()) {
      // Scheduling needs no registration (the handler is never run: the node does not poll).
      bikesched.newJob("not-polling").handler(job -> {}).schedule();
      execute("delete from bikesched_jobs where name = 'not-polling'");

      assertThat(bikesched.node().name()).isEqualTo("node-registers-later");
      assertThat(bikesched.node().id()).isEmpty();
      assertThat(bikesched.node().isPolling()).isFalse();
      assertThat(countRows("bikesched_nodes", "name = 'node-registers-later'")).isZero();

      Node node = bikesched.start();

      assertThat(node).isSameAs(bikesched.node());
      assertThat(node.isPolling()).isTrue();
      UUID id = node.id().orElseThrow();
      assertThat(nodeName(id)).isEqualTo("node-registers-later");
      OffsetDateTime registered = lastHeartbeat(id);
      await()
          .untilAsserted(
              () ->
                  assertThat(lastHeartbeat(id)).as("heartbeats while polling").isAfter(registered));
    }
  }

  @Test
  void closingANodeThatNeverPolledDeregistersNothing() {
    var logs = LogCapture.of(org.bikesched.internal.DefaultBikesched.class);
    try (logs) {
      Bikesched.builder()
          .dataSource(dataSource)
          .nodeName("node-never-polled")
          .autoStartPolling(false)
          .build()
          .close();
    }
    assertThat(logs.messages())
        .contains(
            "bikesched node node-never-polled created; it registers, sends heartbeats and polls"
                + " for jobs once start() is called",
            "bikesched node node-never-polled closed (it never polled, so it was not registered)");
  }

  @Test
  void liveNodesMayShareAName() throws SQLException {
    try (Bikesched first = Bikesched.builder().dataSource(dataSource).nodeName("node-dup").build();
        Bikesched second =
            Bikesched.builder().dataSource(dataSource).nodeName("node-dup").build()) {
      UUID firstId = first.node().id().orElseThrow();
      UUID secondId = second.node().id().orElseThrow();

      assertThat(firstId).isNotEqualTo(secondId);
      assertThat(nodeName(firstId)).isEqualTo("node-dup");
      assertThat(nodeName(secondId)).isEqualTo("node-dup");
    }
  }

  @Test
  void restartedNodeRegistersNextToItsStaleEntryAndReleasesItsJobs() throws SQLException {
    Bikesched.builder()
        .dataSource(dataSource)
        .nodeName("bootstrap")
        .build()
        .close(); // ensure schema
    UUID staleId = UUID.randomUUID();
    // The previous instance crashed a moment ago: its heartbeat is recent, so its row still counts
    // as live, and it was still running a job (scheduled in the future so the new instance's
    // poller does not execute it right after the release).
    execute("insert into bikesched_nodes (id, name) values ('" + staleId + "', 'node-restarted')");
    execute(
        "insert into bikesched_jobs (id, name, state, locked_by_node_id, lock_token, locked_at,"
            + " scheduled_at) values (gen_random_uuid(), 'orphan-restart', 'RUNNING', '"
            + staleId
            + "', gen_random_uuid(), now(), now() + interval '1 hour')");

    try (Bikesched bikesched =
        testNode("node-restarted", Duration.ofMillis(50))
            .nodeTimeout(Duration.ofSeconds(1))
            .nodeCleanUpInterval(Duration.ofMillis(50))
            .build()) {
      assertThat(bikesched.node().id().orElseThrow()).isNotEqualTo(staleId);
      await()
          .untilAsserted(
              () -> {
                assertThat(
                        count(
                            "name = 'orphan-restart' and state = 'SCHEDULED' and locked_by_node_id is null"))
                    .as("released by the node clean-up once the stale entry timed out")
                    .isEqualTo(1);
                assertThat(nodeName(staleId)).isNull();
              });
    }
    execute("delete from bikesched_jobs where name = 'orphan-restart'");
  }

  @Test
  void nodeCleanUpReleasesJobsOfDeadNodesAndThenRemovesThem() throws Exception {
    var logs = LogCapture.of(NodeCleanUp.class);
    UUID deadNode = UUID.randomUUID();
    try (logs;
        Bikesched bikesched =
            Bikesched.builder()
                .dataSource(dataSource)
                .nodeName("node-reaper")
                .heartbeatInterval(Duration.ofMillis(50))
                .nodeTimeout(Duration.ofSeconds(1))
                .nodeCleanUpInterval(Duration.ofMillis(50))
                .pollInterval(Duration.ofMillis(50))
                .build()) {
      bikesched.registerJobHandler("orphan", () -> {});
      execute(
          "insert into bikesched_nodes (id, name, last_heartbeat_at) "
              + "values ('"
              + deadNode
              + "', 'node-dead', now() - interval '1 minute')");
      execute(
          "insert into bikesched_jobs (id, name, state, locked_by_node_id, lock_token, locked_at)"
              + " select gen_random_uuid(), 'orphan', 'RUNNING', '"
              + deadNode
              + "', gen_random_uuid(), now() from generate_series(1, 2)");

      // Released by the node clean-up, then claimed and executed by this node's poller; the dead
      // node itself is removed once its jobs no longer reference it.
      await()
          .untilAsserted(
              () -> {
                assertThat(count("name = 'orphan' and state = 'SUCCEEDED'")).isEqualTo(2);
                assertThat(nodeName(deadNode)).isNull();
              });
    }

    List<String> messages = logs.messages();
    int released =
        indexOfMatch(
            messages,
            "Released 2 running job\\(s\\) of dead bikesched node node-dead \\(.*\\): no heartbeat for PT1S");
    int removed =
        indexOfMatch(
            messages,
            "Removed 1 dead bikesched node\\(s\\) without heartbeat for more than PT2S: "
                + "node-dead \\("
                + deadNode
                + ", last heartbeat .*\\)");
    assertThat(released).as("jobs released").isNotNegative();
    assertThat(removed).as("node removed after its jobs were released").isGreaterThan(released);
  }

  private static int indexOfMatch(List<String> messages, String regex) {
    for (int i = 0; i < messages.size(); i++) {
      if (messages.get(i).matches(regex)) {
        return i;
      }
    }
    return -1;
  }
}
