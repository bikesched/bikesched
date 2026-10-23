// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.util.UUID;
import org.bikesched.job.JobState;

/**
 * A step that published or received an event, see {@link Admin#findEventSteps(UUID)}.
 *
 * @param stepId the step id, see {@link StepInfo#id()}
 * @param jobId the step's job
 * @param jobType the job's type
 * @param jobState the job's current state
 * @param stepName the step name
 * @param kind {@code EVENT_PUBLISH} if the step published the event, {@code EVENT_WAIT} if it
 *     received it
 */
public record EventStep(
    UUID stepId, UUID jobId, String jobType, JobState jobState, String stepName, String kind) {

  /**
   * Returns whether the step published the event.
   *
   * @return {@code true} for an {@code EVENT_PUBLISH} step
   */
  public boolean isPublisher() {
    return "EVENT_PUBLISH".equals(kind);
  }
}
