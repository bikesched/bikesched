// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Instant;
import java.util.Collection;
import java.util.function.Supplier;
import org.bikesched.internal.database.Database;
import org.bikesched.internal.database.NewJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recreates a missing pending occurrence of each recurring job registered on a polling node (e.g.
 * if the last one ended on a node that does not know the recurring job). Run once per node
 * lifetime, when polling starts, before the heartbeat and the clean-ups are scheduled; if it fails,
 * the node retries it every node clean-up interval until it succeeds. Safe to run concurrently on
 * several nodes.
 */
public final class RecurringJobRepair {

  private static final Logger LOG = LoggerFactory.getLogger(RecurringJobRepair.class);

  private final Database database;
  private final Supplier<Collection<RecurringJob>> recurringJobs;

  /**
   * @param database the database
   * @param recurringJobs the recurring jobs registered on this node
   */
  public RecurringJobRepair(Database database, Supplier<Collection<RecurringJob>> recurringJobs) {
    this.database = database;
    this.recurringJobs = recurringJobs;
  }

  /**
   * Runs the repair. Never throws: every recurring job logs and swallows its own failure, so one
   * failing job does not keep the others from being repaired.
   *
   * @return whether every recurring job was checked; {@code false} if any check failed
   */
  public boolean run() {
    return repairRecurringJobs();
  }

  /**
   * Inserts the next occurrence of each recurring job registered on this node that has none
   * pending; a no-op (conflict on the unique index) for all others.
   */
  private boolean repairRecurringJobs() {
    boolean succeeded = true;
    for (RecurringJob recurring : recurringJobs.get()) {
      try {
        Instant now = Instant.now();
        NewJob occurrence = recurring.occurrence(recurring.nextRun(now, now));
        if (database.jobs().insertOccurrence(occurrence)) {
          LOG.info(
              "Recurring job {} had no pending occurrence, scheduled one at {}",
              recurring.type(),
              occurrence.scheduledAt());
        }
      } catch (RuntimeException e) {
        LOG.warn("Checking recurring job {} failed", recurring.type(), e);
        succeeded = false;
      }
    }
    return succeeded;
  }
}
