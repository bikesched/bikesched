package org.bikesched.internal.database;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bikesched.BikeschedException;

/**
 * The {@code bikesched_nodes} table: registering nodes, their heartbeats and removing dead nodes.
 * Thread-safe; every call borrows its own connection from the {@link DatabaseAccess}.
 */
public final class NodeStore {

  private final DatabaseAccess access;

  NodeStore(DatabaseAccess access) {
    this.access = Objects.requireNonNull(access, "access");
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
  public record DeletedNode(UUID nodeId, String nodeName, Instant lastHeartbeatAt) {}

  /**
   * Registers a node under a unique name.
   *
   * @param newNodeId id to use if the name is free
   * @param name the unique node name
   * @param deadAfter heartbeat age after which an existing node with this name counts as dead
   * @return the node id: {@code newNodeId}, or the id of the dead node that was taken over; empty
   *     if a live node already holds the name
   */
  public Optional<UUID> registerNode(UUID newNodeId, String name, Duration deadAfter) {
    return access.withConnection(
        db ->
            db.one(REGISTER_NODE_SQL, row -> row.uuid(1), newNodeId, name, Sql.seconds(deadAfter)),
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
   * Removes a node. Its running jobs must have been released or completed before.
   *
   * @param nodeId the node id
   * @return {@code false} if the node was not registered
   */
  public boolean deregisterNode(UUID nodeId) {
    return access.withConnection(
            db -> db.update(DEREGISTER_NODE_SQL, nodeId),
            e ->
                new BikeschedException("Deregistration of bikesched node " + nodeId + " failed", e))
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
  public List<DeletedNode> deleteDeadNodes(UUID callingNodeId, Duration olderThan) {
    return access.withConnection(
        db ->
            db.list(
                DELETE_DEAD_NODES_SQL,
                row -> new DeletedNode(row.uuid(1), row.string(2), row.instant(3)),
                callingNodeId,
                Sql.seconds(olderThan)),
        e -> new BikeschedException("Deleting dead bikesched nodes failed", e));
  }
}
