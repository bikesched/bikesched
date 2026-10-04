package org.bikesched.internal.event;

import java.util.Objects;
import java.util.UUID;
import org.bikesched.internal.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes expired events (published longer ago than their retention), run by the clean-up thread of
 * a polling node every event clean-up interval. Each event's expiry is fixed when it is published,
 * so all nodes delete the same events, whatever their own default retention. Safe to run
 * concurrently on several nodes.
 */
public final class EventCleanUp {

  private static final Logger LOG = LoggerFactory.getLogger(EventCleanUp.class);

  private final Database database;
  private final UUID nodeId;
  private final String nodeName;

  /**
   * @param database the database
   * @param nodeId the node doing the clean-up, for logging
   * @param nodeName its name, for logging
   */
  public EventCleanUp(Database database, UUID nodeId, String nodeName) {
    this.database = Objects.requireNonNull(database, "database");
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    this.nodeName = Objects.requireNonNull(nodeName, "nodeName");
  }

  /**
   * Runs one clean-up. Never throws: an exception would silently cancel all future clean-ups of the
   * scheduled executor.
   */
  public void run() {
    try {
      int deleted = database.events().deleteExpiredEvents();
      if (deleted > 0) {
        LOG.debug("Deleted {} expired event(s)", deleted);
      }
    } catch (RuntimeException e) {
      LOG.warn("Deleting expired events failed on node {} ({})", nodeName, nodeId, e);
    }
  }
}
