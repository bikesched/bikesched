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
   * Jobs of these names are due now; the node polls if it handles one of them.
   *
   * @param names the job names
   */
  void dueNow(Set<String> names);

  /**
   * A job of this name is due at the given time (possibly already); the node polls then if it
   * handles the name.
   *
   * @param name the job name
   * @param dueAt when the job is due
   */
  void dueAt(String name, Instant dueAt);
}
