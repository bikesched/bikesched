// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.util.Map;
import org.bikesched.job.JobState;
import org.jspecify.annotations.Nullable;

/**
 * The jobs of one type, see {@link Admin#findJobTypes()}.
 *
 * @param type the job type
 * @param counts the number of jobs per state; states without jobs are missing
 * @param recurrence the recurrence of a recurring job type (from its pending occurrence); {@code
 *     null} for other types
 */
public record JobTypeSummary(
    String type, Map<JobState, Integer> counts, @Nullable String recurrence) {

  /** Copies the counts. */
  public JobTypeSummary {
    counts = Map.copyOf(counts);
  }

  /**
   * Returns the number of jobs of this type.
   *
   * @return the sum of all counts
   */
  public int total() {
    return counts.values().stream().mapToInt(Integer::intValue).sum();
  }

  /**
   * Returns the number of jobs of this type in the given state.
   *
   * @param state the state
   * @return the count, {@code 0} if there are none
   */
  public int count(JobState state) {
    return counts.getOrDefault(state, 0);
  }
}
