// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.util.Map;
import org.bikesched.job.JobState;
import org.jspecify.annotations.Nullable;

/**
 * The jobs of one name, see {@link Admin#findJobNames()}.
 *
 * @param name the job name
 * @param counts the number of jobs per state; states without jobs are missing
 * @param recurrence the recurrence of a recurring job (from its pending occurrence); {@code null}
 *     for other names
 * @param handled whether this node can schedule jobs of the name: it has a handler for it, or the
 *     name is one of its {@linkplain org.bikesched.BikeschedBuilder#remoteJobNames(String...)
 *     remote job names}
 */
public record JobNameSummary(
    String name, Map<JobState, Integer> counts, @Nullable String recurrence, boolean handled) {

  /** Copies the counts. */
  public JobNameSummary {
    counts = Map.copyOf(counts);
  }

  /**
   * Returns the number of jobs of this name.
   *
   * @return the sum of all counts
   */
  public int total() {
    return counts.values().stream().mapToInt(Integer::intValue).sum();
  }

  /**
   * Returns the number of jobs of this name in the given state.
   *
   * @param state the state
   * @return the count, {@code 0} if there are none
   */
  public int count(JobState state) {
    return counts.getOrDefault(state, 0);
  }
}
