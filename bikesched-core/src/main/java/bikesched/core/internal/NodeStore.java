package bikesched.core.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code bikesched_nodes} table: registering nodes, their heartbeats and removing dead nodes.
 * Thread-safe; every call borrows its own connection from the {@link Database}.
 */
final class NodeStore {

  private final Database database;

  NodeStore(Database database) {
    this.database = Objects.requireNonNull(database, "database");
  }

  // Inserts the node. If the name is taken by a dead node (stale heartbeat), the restarted node
  // takes over that row and keeps its id; if it is taken by a live node, no row is returned.
  private static final String REGISTER_NODE_SQL =
      """
            insert into bikesched_nodes (id, name) values (?, ?)
            on conflict (name) do update
               set started_at = now(), last_heartbeat_at = now()
             where bikesched_nodes.last_heartbeat_at < now() - make_interval(secs => ?)
            returning id""";

  private static final String HEARTBEAT_SQL =
      "update bikesched_nodes set last_heartbeat_at = now() where id = ?";

  private static final String DEREGISTER_NODE_SQL = "delete from bikesched_nodes where id = ?";

  // Deletes nodes whose heartbeat is older than the given age (excluding the calling node). Nodes
  // still referenced by RUNNING jobs are kept; their jobs are released first by
  // RELEASE_JOBS_OF_DEAD_NODES_SQL. A node re-registering under the same name concurrently
  // refreshes the heartbeat and therefore no longer matches once the row lock is released.
  private static final String DELETE_DEAD_NODES_SQL =
      """
            delete from bikesched_nodes n
             where n.id <> ?
               and n.last_heartbeat_at < now() - make_interval(secs => ?)
               and not exists (select 1 from bikesched_jobs j where j.locked_by = n.id)
            returning n.id, n.name, n.last_heartbeat_at""";

  /**
   * A node removed from {@code bikesched_nodes}.
   *
   * @param nodeId the node id
   * @param nodeName its name
   * @param lastHeartbeatAt its last heartbeat
   */
  record DeletedNode(UUID nodeId, String nodeName, Instant lastHeartbeatAt) {}

  /**
   * Registers a node under a unique name.
   *
   * @param newNodeId id to use if the name is free
   * @param name the unique node name
   * @param deadAfter heartbeat age after which an existing node with this name counts as dead
   * @return the node id: {@code newNodeId}, or the id of the dead node that was taken over; empty
   *     if a live node already holds the name
   */
  Optional<UUID> registerNode(UUID newNodeId, String name, Duration deadAfter) {
    return database.withConnection(
        "Registering bikesched node '" + name + "'",
        db ->
            db.one(REGISTER_NODE_SQL, row -> row.uuid(1), newNodeId, name, Sql.seconds(deadAfter)));
  }

  /**
   * Updates the heartbeat of a node.
   *
   * @param nodeId the node id
   * @return {@code false} if the node is no longer registered
   */
  boolean heartbeat(UUID nodeId) {
    return database.update(HEARTBEAT_SQL, "Heartbeat of bikesched node " + nodeId, nodeId) > 0;
  }

  /**
   * Removes a node. Its running jobs must have been released or completed before.
   *
   * @param nodeId the node id
   * @return {@code false} if the node was not registered
   */
  boolean deregisterNode(UUID nodeId) {
    return database.update(
            DEREGISTER_NODE_SQL, "Deregistration of bikesched node " + nodeId, nodeId)
        > 0;
  }

  /**
   * Deletes nodes without heartbeat for longer than {@code olderThan} that no longer hold {@code
   * RUNNING} jobs.
   *
   * @param callingNodeId the node doing the cleanup; never deleted
   * @param olderThan minimum heartbeat age of deleted nodes
   * @return the deleted nodes
   */
  List<DeletedNode> deleteDeadNodes(UUID callingNodeId, Duration olderThan) {
    return database.withConnection(
        "Deleting dead bikesched nodes",
        db ->
            db.list(
                DELETE_DEAD_NODES_SQL,
                row -> new DeletedNode(row.uuid(1), row.string(2), row.instant(3)),
                callingNodeId,
                Sql.seconds(olderThan)));
  }
}
