// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import org.bikesched.job.JobState;

/** What a job was claimed for, decided once when the claimed row is read. */
public sealed interface JobPhase {

  /** The number of the claimed attempt, compensation attempt or failure handler run. */
  int attempt();

  /**
   * The job runs its handler.
   *
   * @param attempt the number of attempts including the claimed one
   */
  record Run(int attempt) implements JobPhase {}

  /**
   * The job compensates its steps ({@code COMPENSATING}).
   *
   * @param compensatedState {@code CANCELLED} or {@code FAILED}: what the job is compensated for
   * @param attempt the number of compensation attempts including the claimed one
   */
  record Compensate(JobState compensatedState, int attempt) implements JobPhase {}

  /**
   * The job runs its failure handler ({@code FAILING}).
   *
   * @param endState {@code FAILED} or {@code COMPENSATION_FAILED}: the state the job ends in
   * @param attempt the number of runs of the failure handler including the claimed one
   */
  record HandleFailure(JobState endState, int attempt) implements JobPhase {}
}
