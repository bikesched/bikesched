// SPDX-License-Identifier: Apache-2.0
package org.bikesched.event.listener;

/** Where an event was published, see {@link EventPublication}. */
public enum EventSource {
  /** Published through {@link org.bikesched.Bikesched#newEvent(String, String)}. */
  NODE,
  /** Published by a job as a step, through {@link org.bikesched.job.Job}'s {@code newEvent}. */
  JOB
}
