// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.time.Instant;
import java.util.Set;

/**
 * This node's wake-ups for jobs that become due: implemented by the poller (in {@code
 * internal.job.claim}, which this package does not depend on) and called by the {@link
 * DueJobsNotifier} with the jobs the node's own writes made due.
 */
public interface DueTimes {

  /**
   * Jobs of these types are due now; the node polls if it handles one of them.
   *
   * @param types the job types
   */
  void dueNow(Set<String> types);

  /**
   * A job of this type is due at the given time (possibly already); the node polls then if it
   * handles the type.
   *
   * @param type the job type
   * @param dueAt when the job is due
   */
  void dueAt(String type, Instant dueAt);
}
