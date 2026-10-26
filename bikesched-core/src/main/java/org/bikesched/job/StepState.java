// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

/**
 * The state of a step of a job, see {@link org.bikesched.history.StepEntry#state()}.
 *
 * @see Job#newStep(String, Class)
 */
public enum StepState {

  /** Its handler (or a check of a poll) runs. */
  RUNNING,

  /** It waits for an event, a child job, its wake-up time or its next check. */
  WAITING,

  /** It completed; its result is stored. */
  SUCCEEDED,

  /** It failed in the job's last attempt; its failure handler has to run (again). */
  FAILING,

  /** It failed. */
  FAILED,

  /** Its wait or poll timed out. */
  TIMED_OUT,

  /** Its compensation ran. */
  COMPENSATED,

  /** Its job was cancelled before it completed. */
  CANCELLED,

  /** Its handler postponed the job. */
  POSTPONED
}
