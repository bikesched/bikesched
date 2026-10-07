// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.io.Serial;
import java.time.Instant;
import org.bikesched.event.EventWait;
import org.bikesched.job.Job;

/**
 * Thrown by {@link EventWait#receive(Class)} and {@link Job#sleep} to end the handler's attempt
 * while the job waits for an event or sleeps; bikesched then suspends the job (state {@code
 * WAITING}) and runs the handler again once the event is published or the job wakes up.
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
        "Job suspended in step '" + suspension.stepName() + "' " + suspension, null, false, false);
    this.suspension = suspension;
  }

  Suspension suspension() {
    return suspension;
  }

  /** A step a job is suspended at: waiting for an event, or sleeping. */
  sealed interface Suspension permits EventSuspension, SleepSuspension {

    /** The waiting step. */
    String stepName();

    /** The deadline of an event wait ({@code null} if none) or the wake-up time of a sleep. */
    Instant waitUntil();
  }

  /**
   * A step waiting for an event.
   *
   * @param stepName the waiting step
   * @param eventName the event it waits for
   * @param eventKey the event key
   * @param waitUntil the deadline of the wait, {@code null} if none
   */
  record EventSuspension(String stepName, String eventName, String eventKey, Instant waitUntil)
      implements Suspension {

    /** The event, e.g. {@code event payment-received/42}. */
    String event() {
      return "event " + eventName + "/" + eventKey;
    }

    @Override
    public String toString() {
      return "to wait for " + event() + (waitUntil == null ? "" : " until " + waitUntil);
    }
  }

  /**
   * A sleeping step.
   *
   * @param stepName the sleeping step
   * @param waitUntil when the job wakes up
   */
  record SleepSuspension(String stepName, Instant waitUntil) implements Suspension {

    @Override
    public String toString() {
      return "to sleep until " + waitUntil;
    }
  }
}
