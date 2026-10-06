// SPDX-License-Identifier: Apache-2.0
package org.bikesched.annotation;

import org.bikesched.job.Recurrence;

/** Parses a schedule expression used by {@link JobHandler#schedule()}. */
public interface ScheduleProvider {

  /**
   * Whether this provider recognizes the expression.
   *
   * @param schedule the schedule expression
   * @return whether this provider can parse it
   */
  boolean supports(String schedule);

  /**
   * Parses the expression.
   *
   * @param schedule the schedule expression
   * @return the recurrence
   */
  Recurrence parse(String schedule);
}
