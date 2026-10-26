// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.bikesched.history.NodeAction;
import org.bikesched.internal.database.HistoryStore.Change;

/**
 * The {@code bikesched_nodes} table: registering nodes, their heartbeats and removing dead nodes.
 * Thread-safe; every call borrows its own connection from the {@link DatabaseAccess}.
 */
public final class NodeStore {

  private final DatabaseAccess access;
  private final HistoryStore history;

  NodeStore(DatabaseAccess access, HistoryStore history) {
    this.access = access;
    this.history = history;
  }

  // Names need not be unique: a restarted instance registers next to the stale row of its
  // previous run, which the node clean-up releases and removes like any dead node.
  private static final String REGISTER_NODE_SQL =
      "insert into bikesched_nodes (id, name) values (?, ?)";

  // Registers a removed node again under its own id.
  private static final String REREGISTER_NODE_SQL =
      "insert into bikesched_nodes (id, name) values (?, ?) on conflict (id) do nothing";

  private static final String HEARTBEAT_SQL =
      "update bikesched_nodes set last_heartbeat_at = now() where id = ?";

  private static final String DEREGISTER_NODE_SQL =
      "delete from bikesched_nodes where id = ? returning name";

  // Deletes nodes whose heartbeat is older than the given age (excluding the calling node). Nodes
  // still referenced by RUNNING jobs are kept; their jobs are released first by
  // RELEASE_JOBS_OF_DEAD_NODES_SQL. A node re-registering under the same name concurrently
  // refreshes the heartbeat and therefore no longer matches once the row lock is released.
  private static final String DELETE_DEAD_NODES_SQL =
      """
            delete from bikesched_nodes n
             where n.id <> ?
               and n.last_heartbeat_at < now() - make_interval(secs => ?)
               and not exists (select 1 from bikesched_jobs j where j.locked_by_node_id = n.id)
            returning n.id, n.name, n.last_heartbeat_at""";

  /**
   * A node removed from {@code bikesched_nodes}.
   *
   * @param nodeId the node id
   * @param nodeName its name
   * @param lastHeartbeatAt its last heartbeat
   */
  public record DeletedNode(UUID nodeId, String nodeName, Instant lastHeartbeatAt) {}

  /**
   * Registers a node.
   *
   * @param nodeId the new node's id
   * @param name the node name, not necessarily unique
   */
  public void registerNode(UUID nodeId, String name) {
    history.change(
        db -> {
          db.update(REGISTER_NODE_SQL, nodeId, name);
          history.record(db, Change.node(nodeId, NodeAction.REGISTERED));
          return null;
        },
        e -> new BikeschedException("Registering bikesched node '" + name + "' failed", e));
  }

  /**
   * Updates the heartbeat of a node.
   *
   * @param nodeId the node id
   * @return {@code false} if the node is no longer registered
   */
  public boolean heartbeat(UUID nodeId) {
    return access.withConnection(
            db -> db.update(HEARTBEAT_SQL, nodeId),
            e -> new BikeschedException("Heartbeat of bikesched node " + nodeId + " failed", e))
        > 0;
  }

  /**
   * Registers a node again that was removed while it was still running, e.g. after a pause longer
   * than twice the node timeout: under its own id, so its claims stay its own.
   *
   * @param nodeId the node's id
   * @param name the node's name
   */
  public void reregisterNode(UUID nodeId, String name) {
    history.change(
        db -> {
          if (db.update(REREGISTER_NODE_SQL, nodeId, name) > 0) {
            history.record(db, Change.node(nodeId, NodeAction.REGISTERED));
          }
          return null;
        },
        e -> new BikeschedException("Registering bikesched node '" + name + "' failed", e));
  }

  /**
   * Removes a node. Its running jobs must have been released or completed before.
   *
   * @param nodeId the node id
   * @return {@code false} if the node was not registered
   */
  public boolean deregisterNode(UUID nodeId) {
    return history.change(
        db -> {
          Optional<String> name = db.one(DEREGISTER_NODE_SQL, row -> row.string("name"), nodeId);
          if (name.isPresent()) {
            history.record(db, Change.node(nodeId, NodeAction.STOPPED).subjectName(name.get()));
          }
          return name.isPresent();
        },
        e -> new BikeschedException("Deregistration of bikesched node " + nodeId + " failed", e));
  }

  /**
   * Deletes nodes without heartbeat for longer than {@code olderThan} that no longer hold {@code
   * RUNNING} jobs.
   *
   * @param callingNodeId the node doing the cleanup; never deleted
   * @param olderThan minimum heartbeat age of deleted nodes
   * @return the deleted nodes
   */
  public List<DeletedNode> deleteDeadNodes(UUID callingNodeId, Duration olderThan) {
    return history.change(
        db -> {
          List<DeletedNode> deleted =
              db.list(
                  DELETE_DEAD_NODES_SQL,
                  row ->
                      new DeletedNode(
                          row.uuid("id"), row.string("name"), row.instant("last_heartbeat_at")),
                  callingNodeId,
                  Sql.seconds(olderThan));
          history.record(
              db,
              deleted.stream()
                  .map(
                      node ->
                          Change.node(node.nodeId(), NodeAction.REMOVED)
                              .writtenBy(callingNodeId)
                              .subjectName(node.nodeName()))
                  .toList());
          return deleted;
        },
        e -> new BikeschedException("Deleting dead bikesched nodes failed", e));
  }
}
