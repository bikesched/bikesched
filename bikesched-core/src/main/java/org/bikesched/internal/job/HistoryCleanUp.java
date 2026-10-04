package org.bikesched.internal.job;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.internal.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes job history entries older than the job history retention, run by the clean-up thread of a
 * polling node with the job history enabled, every job history clean-up interval. Safe to run
 * concurrently on several nodes.
 */
public final class HistoryCleanUp {

  private static final Logger LOG = LoggerFactory.getLogger(HistoryCleanUp.class);

  private final Database database;
  private final UUID nodeId;
  private final String nodeName;
  private final Duration retention;

  /**
   * @param database the database
   * @param nodeId the node doing the clean-up, for logging
   * @param nodeName its name, for logging
   * @param retention how long job history entries are kept
   */
  public HistoryCleanUp(Database database, UUID nodeId, String nodeName, Duration retention) {
    this.database = Objects.requireNonNull(database, "database");
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    this.nodeName = Objects.requireNonNull(nodeName, "nodeName");
    this.retention = Objects.requireNonNull(retention, "retention");
  }

  /**
   * Runs one clean-up. Never throws: an exception would silently cancel all future clean-ups of the
   * scheduled executor.
   */
  public void run() {
    try {
      int deleted = database.history().deleteHistory(retention);
      if (deleted > 0) {
        LOG.debug("Deleted {} job history entries older than {}", deleted, retention);
      }
    } catch (RuntimeException e) {
      LOG.warn("Deleting old job history failed on node {} ({})", nodeName, nodeId, e);
    }
  }
}
