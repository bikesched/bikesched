// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The runs (occurrences) of a recurring job, see {@link Admin#findRecurringJobRuns(String, int)}.
 *
 * @param name the job name identifying the recurring job
 * @param pending the occurrence that has not ended yet: scheduled for its next run, or running or
 *     waiting; {@code null} if there is none, e.g. after the recurring job was removed
 * @param previous the ended occurrences, newest first
 */
public record RecurringJobRuns(
    String name, @Nullable JobSummary pending, List<JobSummary> previous) {

  /** Copies the previous runs. */
  public RecurringJobRuns {
    previous = List.copyOf(previous);
  }
}
