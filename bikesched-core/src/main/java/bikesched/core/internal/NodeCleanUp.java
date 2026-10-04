package bikesched.core.internal;

import bikesched.core.internal.database.Database;
import bikesched.core.internal.database.NodeStore;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The node housekeeping of a polling node, run by its clean-up thread every node clean-up interval:
 *
 * <ol>
 *   <li>releases the {@code RUNNING} jobs of dead nodes (no heartbeat for the node timeout) for
 *       retry by other nodes
 *   <li>removes nodes without heartbeat for twice the node timeout
 * </ol>
 *
 * <p>Every polling node does all of it; the statements are safe to run concurrently on several
 * nodes.
 */
public final class NodeCleanUp {

  private static final Logger LOG = LoggerFactory.getLogger(NodeCleanUp.class);

  private final Database database;
  private final UUID nodeId;
  private final String nodeName;
  private final Duration nodeTimeout;

  /**
   * @param database the database
   * @param nodeId the node doing the clean-up; never treated as dead
   * @param nodeName its name, for logging
   * @param nodeTimeout a node without heartbeat for this long is considered dead
   */
  NodeCleanUp(Database database, UUID nodeId, String nodeName, Duration nodeTimeout) {
    this.database = Objects.requireNonNull(database, "database");
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    this.nodeName = Objects.requireNonNull(nodeName, "nodeName");
    this.nodeTimeout = Objects.requireNonNull(nodeTimeout, "nodeTimeout");
  }

  /**
   * Runs one clean-up. Never throws: an exception would silently cancel all future clean-ups of the
   * scheduled executor, so every part logs and swallows its own failures.
   */
  void run() {
    releaseJobsOfDeadNodes();
    deleteDeadNodes();
  }

  /** Puts jobs of nodes without heartbeat for the node timeout back to SCHEDULED. */
  private void releaseJobsOfDeadNodes() {
    try {
      for (var dead : database.jobs().releaseJobsOfDeadNodes(nodeId, nodeTimeout)) {
        LOG.warn(
            "Released {} running job(s) of dead bikesched node {} ({}): no heartbeat for {}",
            dead.releasedJobs(),
            dead.nodeName(),
            dead.nodeId(),
            nodeTimeout);
      }
    } catch (RuntimeException e) {
      LOG.warn(
          "Releasing jobs of dead bikesched nodes failed on node {} ({})", nodeName, nodeId, e);
    }
  }

  /**
   * Removes nodes without heartbeat for twice the node timeout. Runs after {@link
   * #releaseJobsOfDeadNodes()}, so their jobs are free by then. The grace period beyond the timeout
   * keeps the row (and id) around for a crashed node that restarts under the same name shortly
   * after.
   */
  private void deleteDeadNodes() {
    try {
      Duration olderThan = nodeTimeout.multipliedBy(2);
      List<NodeStore.DeletedNode> deleted = database.nodes().deleteDeadNodes(nodeId, olderThan);
      if (!deleted.isEmpty()) {
        LOG.info(
            "Removed {} dead bikesched node(s) without heartbeat for more than {}: {}",
            deleted.size(),
            olderThan,
            deleted.stream()
                .map(
                    n ->
                        n.nodeName()
                            + " ("
                            + n.nodeId()
                            + ", last heartbeat "
                            + n.lastHeartbeatAt()
                            + ")")
                .collect(Collectors.joining(", ")));
      }
    } catch (RuntimeException e) {
      LOG.warn("Removing dead bikesched nodes failed on node {} ({})", nodeName, nodeId, e);
    }
  }
}
