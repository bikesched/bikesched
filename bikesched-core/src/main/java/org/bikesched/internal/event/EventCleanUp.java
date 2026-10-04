package org.bikesched.internal.event;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.internal.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes events published longer ago than the event retention, run by the clean-up thread of a
 * polling node every event clean-up interval. Safe to run concurrently on several nodes.
 */
public final class EventCleanUp {

  private static final Logger LOG = LoggerFactory.getLogger(EventCleanUp.class);

  private final Database database;
  private final UUID nodeId;
  private final String nodeName;
  private final Duration eventRetention;

  /**
   * @param database the database
   * @param nodeId the node doing the clean-up, for logging
   * @param nodeName its name, for logging
   * @param eventRetention how long published events are kept
   */
  public EventCleanUp(Database database, UUID nodeId, String nodeName, Duration eventRetention) {
    this.database = Objects.requireNonNull(database, "database");
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    this.nodeName = Objects.requireNonNull(nodeName, "nodeName");
    this.eventRetention = Objects.requireNonNull(eventRetention, "eventRetention");
  }

  /**
   * Runs one clean-up. Never throws: an exception would silently cancel all future clean-ups of the
   * scheduled executor.
   */
  public void run() {
    deleteOldEvents();
  }

  /** Deletes events published longer ago than the event retention. */
  private void deleteOldEvents() {
    try {
      int deleted = database.events().deleteEvents(eventRetention);
      if (deleted > 0) {
        LOG.debug("Deleted {} event(s) published more than {} ago", deleted, eventRetention);
      }
    } catch (RuntimeException e) {
      LOG.warn("Deleting old events failed on node {} ({})", nodeName, nodeId, e);
    }
  }
}
