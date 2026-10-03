package bikesched.core;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The periodic housekeeping of a node, run by its heartbeat thread every heartbeat interval:
 *
 * <ol>
 *   <li>updates the node's heartbeat in {@code bikesched_nodes}
 *   <li>releases the {@code RUNNING} jobs of dead nodes (no heartbeat for the node timeout) for
 *       retry by other nodes
 *   <li>removes nodes without heartbeat for twice the node timeout
 *   <li>deletes events older than the event retention
 * </ol>
 *
 * <p>Every node does all of it; the statements are safe to run concurrently on several nodes.
 */
final class Heartbeat {

  private static final Logger LOG = LoggerFactory.getLogger(Heartbeat.class);

  private final Database database;
  private final UUID nodeId;
  private final String nodeName;
  private final Duration nodeTimeout;
  private final Duration eventRetention;

  /**
   * @param database the database
   * @param nodeId the node sending the heartbeat
   * @param nodeName its name, for logging
   * @param nodeTimeout a node without heartbeat for this long is considered dead
   * @param eventRetention how long published events are kept
   */
  Heartbeat(
      Database database,
      UUID nodeId,
      String nodeName,
      Duration nodeTimeout,
      Duration eventRetention) {
    this.database = Objects.requireNonNull(database, "database");
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    this.nodeName = Objects.requireNonNull(nodeName, "nodeName");
    this.nodeTimeout = Objects.requireNonNull(nodeTimeout, "nodeTimeout");
    this.eventRetention = Objects.requireNonNull(eventRetention, "eventRetention");
  }

  /**
   * Runs one heartbeat. Never throws: an exception would silently cancel all future heartbeats of
   * the scheduled executor, so every part logs and swallows its own failures.
   */
  void run() {
    try {
      if (database.heartbeat(nodeId)) {
        LOG.trace("Heartbeat of bikesched node {} ({})", nodeName, nodeId);
      } else {
        LOG.warn("bikesched node {} ({}) is no longer registered", nodeName, nodeId);
      }
    } catch (RuntimeException e) {
      LOG.warn("Heartbeat of bikesched node {} ({}) failed", nodeName, nodeId, e);
    }
    releaseJobsOfDeadNodes();
    deleteDeadNodes();
    deleteOldEvents();
  }

  /** Deletes events published longer ago than the event retention. */
  private void deleteOldEvents() {
    try {
      int deleted = database.deleteEvents(eventRetention);
      if (deleted > 0) {
        LOG.debug("Deleted {} event(s) published more than {} ago", deleted, eventRetention);
      }
    } catch (RuntimeException e) {
      LOG.warn("Deleting old events failed on node {} ({})", nodeName, nodeId, e);
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
      List<Database.DeletedNode> deleted = database.deleteDeadNodes(nodeId, olderThan);
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

  /** Puts jobs of nodes without heartbeat for the node timeout back to SCHEDULED. */
  private void releaseJobsOfDeadNodes() {
    try {
      for (var dead : database.releaseJobsOfDeadNodes(nodeId, nodeTimeout)) {
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
}
