// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Duration;
import java.util.UUID;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.database.DeletedBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes ended jobs (with their steps) that finished longer than the job retention ago, run by the
 * clean-up thread of every polling node, every job clean-up interval. Deletes in batches, each in
 * its own short transaction, so a large backlog does not hold locks for long (see {@link
 * DeletedBatch}). Safe to run concurrently on several nodes.
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
    this.database = database;
    this.nodeId = nodeId;
    this.nodeName = nodeName;
    this.retention = retention;
  }

  /**
   * Runs one clean-up: deletes batches until a batch is not full. Never throws: an exception would
   * silently cancel all future clean-ups of the scheduled executor.
   */
  public void run() {
    int deleted = 0;
    try {
      deleted =
          DeletedBatch.deleteAll(
              BATCH_SIZE,
              after -> database.jobs().deleteFinishedJobs(after, retention, BATCH_SIZE));
    } catch (RuntimeException e) {
      LOG.warn("Deleting finished jobs failed on node {} ({})", nodeName, nodeId, e);
    }
    if (deleted > 0) {
      LOG.debug("Deleted {} job(s) finished more than {} ago", deleted, retention);
    }
  }
}
