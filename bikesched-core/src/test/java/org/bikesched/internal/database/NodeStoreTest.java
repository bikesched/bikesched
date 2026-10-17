// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NodeStoreTest extends AbstractStoreTest {

  @Test
  void registersNodesWithTheSameName() {
    UUID first = registerNode("node-1");
    UUID second = registerNode("node-1");

    assertThat(database.nodes().heartbeat(first)).isTrue();
    assertThat(database.nodes().heartbeat(second)).isTrue();
  }

  @Test
  void reregisteringARegisteredNodeKeepsIt() {
    UUID id = registerNode("node-1");

    database.nodes().reregisterNode(id, "node-1");

    assertThat(database.nodes().heartbeat(id)).isTrue();
  }

  @Test
  void heartbeatUpdatesRegisteredNode() throws SQLException {
    UUID id = UUID.randomUUID();
    database.nodes().registerNode(id, "node-1");
    execute("update bikesched_nodes set last_heartbeat_at = now() - interval '1 minute'");

    assertThat(database.nodes().heartbeat(id)).isTrue();
    assertThat(lastHeartbeat(id)).isAfter(OffsetDateTime.now().minusSeconds(10));
  }

  @Test
  void heartbeatOfUnknownNodeReturnsFalse() {
    assertThat(database.nodes().heartbeat(UUID.randomUUID())).isFalse();
  }

  @Test
  void deregisterRemovesNode() {
    UUID id = UUID.randomUUID();
    database.nodes().registerNode(id, "node-1");

    assertThat(database.nodes().deregisterNode(id)).isTrue();
    assertThat(database.nodes().heartbeat(id)).isFalse();
    assertThat(database.nodes().deregisterNode(id)).isFalse();
  }

  @Test
  void deleteDeadNodesRemovesOnlyLongDeadNodesWithoutRunningJobs() throws SQLException {
    UUID alive = registerNode("alive");
    UUID recentlyDead = registerNode("recently-dead");
    UUID longDead = registerNode("long-dead");
    UUID longDeadWithJob = registerNode("long-dead-busy");
    UUID caller = registerNode("caller");
    execute(
        "update bikesched_nodes set last_heartbeat_at = now() - interval '45 seconds' where id = '"
            + recentlyDead
            + "'");
    execute(
        "update bikesched_nodes set last_heartbeat_at = now() - interval '2 minutes' where id in ('"
            + longDead
            + "', '"
            + longDeadWithJob
            + "', '"
            + caller
            + "')");
    insertRunningJobs(longDeadWithJob, 1);

    assertThat(database.nodes().deleteDeadNodes(caller, Duration.ofSeconds(60)))
        .singleElement()
        .satisfies(
            node -> {
              assertThat(node.nodeId()).isEqualTo(longDead);
              assertThat(node.nodeName()).isEqualTo("long-dead");
              assertThat(node.lastHeartbeatAt()).isBefore(Instant.now().minusSeconds(60));
            });

    assertThat(database.nodes().heartbeat(alive)).isTrue();
    assertThat(database.nodes().heartbeat(recentlyDead))
        .as("dead, but not for twice the timeout yet")
        .isTrue();
    assertThat(database.nodes().heartbeat(longDeadWithJob))
        .as("still referenced by a running job")
        .isTrue();
    assertThat(database.nodes().heartbeat(caller)).as("the calling node is never deleted").isTrue();
    assertThat(database.nodes().heartbeat(longDead)).isFalse();
  }
}
