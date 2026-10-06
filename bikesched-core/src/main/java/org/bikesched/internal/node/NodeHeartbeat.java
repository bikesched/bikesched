// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.node;

import java.util.Objects;
import java.util.UUID;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.event.EventCleanUp;
import org.bikesched.internal.job.RecurringJobRepair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The heartbeat of a polling node, run by its heartbeat thread every heartbeat interval: updates
 * the node's heartbeat in {@code bikesched_nodes}, so other nodes don't consider it dead and
 * release its running jobs. The more expensive housekeeping runs on its own intervals, see {@link
 * NodeCleanUp}, {@link EventCleanUp} and {@link RecurringJobRepair}.
 */
public final class NodeHeartbeat {

  private static final Logger LOG = LoggerFactory.getLogger(NodeHeartbeat.class);

  private final Database database;
  private final UUID nodeId;
  private final String nodeName;

  /**
   * @param database the database
   * @param nodeId the node sending the heartbeat
   * @param nodeName its name, for logging
   */
  public NodeHeartbeat(Database database, UUID nodeId, String nodeName) {
    this.database = Objects.requireNonNull(database, "database");
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    this.nodeName = Objects.requireNonNull(nodeName, "nodeName");
  }

  /**
   * Runs one heartbeat. Never throws: an exception would silently cancel all future heartbeats of
   * the scheduled executor.
   */
  public void run() {
    updateNodeHeartbeat();
  }

  /** Updates the node entry in the database. */
  private void updateNodeHeartbeat() {
    try {
      if (database.nodes().heartbeat(nodeId)) {
        LOG.trace("Heartbeat of bikesched node {} ({})", nodeName, nodeId);
      } else {
        LOG.warn("bikesched node {} ({}) is no longer registered", nodeName, nodeId);
      }
    } catch (RuntimeException e) {
      LOG.warn("Heartbeat of bikesched node {} ({}) failed", nodeName, nodeId, e);
    }
  }
}
