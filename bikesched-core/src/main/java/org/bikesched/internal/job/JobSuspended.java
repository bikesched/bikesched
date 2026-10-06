// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.io.Serial;
import java.time.Instant;
import org.bikesched.event.EventWait;

/**
 * Thrown by {@link EventWait#receive(Class)} to end the handler's attempt while the job waits for
 * an event; bikesched then suspends the job (state {@code WAITING}) and runs the handler again once
 * the event is published.
 *
 * <p>An {@link Error} rather than an exception, so a {@code catch (Exception e)} in the handler
 * does not swallow it by accident. Even if the handler catches it anyway, the job is suspended once
 * the handler returns. Created without a stack trace: it is control flow, not a failure.
 */
final class JobSuspended extends Error {

  @Serial private static final long serialVersionUID = 1L;

  /** The waiting step; not serialized (control flow within one JVM). */
  private final transient Suspension suspension;

  JobSuspended(Suspension suspension) {
    super(
        "Job suspended in step '"
            + suspension.stepName()
            + "' to wait for event "
            + suspension.eventName()
            + "/"
            + suspension.eventKey()
            + (suspension.waitUntil() == null ? "" : " until " + suspension.waitUntil()),
        null,
        false,
        false);
    this.suspension = suspension;
  }

  Suspension suspension() {
    return suspension;
  }

  /**
   * A step a job is suspended at.
   *
   * @param stepName the waiting step
   * @param eventName the event it waits for
   * @param eventKey the event key
   * @param waitUntil the deadline of the wait, {@code null} if none
   */
  record Suspension(String stepName, String eventName, String eventKey, Instant waitUntil) {}
}
