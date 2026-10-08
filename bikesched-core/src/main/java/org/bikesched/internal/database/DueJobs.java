// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.time.Instant;
import java.util.Set;

/**
 * Receives the jobs the stores' writes made due, after the writes committed: the stores report them
 * on their {@link Sql} handle ({@link Sql#dueNow}, {@link Sql#dueAt}), and {@link DatabaseAccess}
 * passes them on once the transaction committed (nothing on a rollback). Writes on a caller's
 * connection instead notify inside the caller's transaction ({@link #isNotifying()}), since
 * bikesched does not see that commit.
 *
 * <p>Implementations must be thread-safe, return quickly and must not throw.
 */
public interface DueJobs {

  /** Ignores everything; the default until the node sets its own. */
  DueJobs NONE =
      new DueJobs() {
        @Override
        public void dueNow(Set<String> types) {}

        @Override
        public void dueAt(String type, Instant dueAt) {}

        @Override
        public boolean isNotifying() {
          return false;
        }
      };

  /**
   * Jobs of these types are due now, e.g. new jobs, resumed or released jobs: every node handling
   * them should poll.
   *
   * @param types the job types, not empty
   */
  void dueNow(Set<String> types);

  /**
   * A job of this type, written by the reporting node, is due at the given time (possibly already):
   * e.g. a suspension until a deadline, a retry with a delay or a delayed job. Only for the
   * reporting node's own wake-up, other nodes are not told.
   *
   * @param type the job type
   * @param dueAt when the job is due
   */
  void dueAt(String type, Instant dueAt);

  /**
   * Whether writes on a caller's connection send their notification inside the caller's
   * transaction, see {@link JobStore#NOTIFY_DUE_SQL}.
   */
  boolean isNotifying();
}
