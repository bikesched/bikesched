// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.node;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.database.NodeStore;
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
  private final Duration inactiveJobGrace;
  private final ActiveJobs activeJobs;

  /**
   * @param database the database
   * @param nodeId the node doing the clean-up; never treated as dead
   * @param nodeName its name, for logging
   * @param nodeTimeout a node without heartbeat for this long is considered dead
   * @param inactiveJobGrace minimum claim age before recovering inactive jobs of this node
   * @param activeJobs the jobs whose attempts still run in memory
   */
  public NodeCleanUp(
      Database database,
      UUID nodeId,
      String nodeName,
      Duration nodeTimeout,
      Duration inactiveJobGrace,
      ActiveJobs activeJobs) {
    this.database = database;
    this.nodeId = nodeId;
    this.nodeName = nodeName;
    this.nodeTimeout = nodeTimeout;
    this.inactiveJobGrace = inactiveJobGrace;
    this.activeJobs = activeJobs;
  }

  /** The jobs of this node whose attempts still run in memory. */
  @FunctionalInterface
  public interface ActiveJobs {

    /**
     * Runs the release with the ids of the active jobs, while no job is claimed: a job claimed in
     * the database is active by the time the release runs. Skips it while jobs are being claimed.
     *
     * @param release releases the node's jobs that are not active
     * @return the number of released jobs, {@code 0} if skipped
     */
    int withActiveJobIds(ToIntFunction<Set<UUID>> release);
  }

  /**
   * Runs one clean-up. Never throws: an exception would silently cancel all future clean-ups of the
   * scheduled executor, so every part logs and swallows its own failures.
   */
  public void run() {
    releaseInactiveJobs();
    releaseJobsOfDeadNodes();
    deleteDeadNodes();
  }

  /** Releases stale jobs of this live node once their attempt has ended in memory. */
  private void releaseInactiveJobs() {
    try {
      int released =
          activeJobs.withActiveJobIds(
              active -> database.jobs().releaseInactiveJobs(nodeId, inactiveJobGrace, active));
      if (released > 0) {
        LOG.warn(
            "Released {} inactive job(s) of bikesched node {} ({}) after no outcome was recorded",
            released,
            nodeName,
            nodeId);
      }
    } catch (RuntimeException e) {
      LOG.warn("Releasing inactive jobs failed on bikesched node {} ({})", nodeName, nodeId, e);
    }
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
   * keeps a dead node visible as offline for a while.
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
