// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.internal.database.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes ended jobs (with their steps) that finished longer than the job retention ago, run by the
 * clean-up thread of every polling node, every job clean-up interval. Deletes in batches, each in
 * its own short transaction, so a large backlog does not hold locks for long. Safe to run
 * concurrently on several nodes.
 */
public final class JobCleanUp {

  private static final Logger LOG = LoggerFactory.getLogger(JobCleanUp.class);

  /** Jobs deleted per statement. */
  static final int BATCH_SIZE = 1000;

  private final Database database;
  private final UUID nodeId;
  private final String nodeName;
  private final Duration retention;

  /**
   * @param database the database
   * @param nodeId the node doing the clean-up, for logging
   * @param nodeName its name, for logging
   * @param retention how long ended jobs are kept
   */
  public JobCleanUp(Database database, UUID nodeId, String nodeName, Duration retention) {
    this.database = Objects.requireNonNull(database, "database");
    this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    this.nodeName = Objects.requireNonNull(nodeName, "nodeName");
    this.retention = Objects.requireNonNull(retention, "retention");
  }

  /**
   * Runs one clean-up: deletes batches until a batch is not full. Never throws: an exception would
   * silently cancel all future clean-ups of the scheduled executor.
   */
  public void run() {
    int deleted = 0;
    try {
      int batch;
      do {
        if (Thread.currentThread().isInterrupted()) {
          break;
        }
        batch = database.jobs().deleteFinishedJobs(retention, BATCH_SIZE);
        deleted += batch;
      } while (batch == BATCH_SIZE);
    } catch (RuntimeException e) {
      LOG.warn("Deleting finished jobs failed on node {} ({})", nodeName, nodeId, e);
    }
    if (deleted > 0) {
      LOG.debug("Deleted {} job(s) finished more than {} ago", deleted, retention);
    }
  }
}
