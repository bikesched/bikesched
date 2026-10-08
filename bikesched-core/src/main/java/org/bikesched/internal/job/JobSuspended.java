// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.job;

import java.io.Serial;
import java.time.Instant;
import java.util.UUID;
import org.bikesched.event.EventWait;
import org.bikesched.job.Job;

/**
 * Thrown by {@link EventWait#receive(Class)}, {@link Job#sleep}, {@link Job#waitForJob} and polls
 * to end the handler's attempt while the job waits for an event, sleeps, waits for a child job or
 * for the next check of a poll; bikesched then suspends the job (state {@code WAITING}) and runs
 * the handler again once the event is published, the job wakes up, the child ended or the next
 * check is due.
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

  /** A step a job is suspended at: waiting for an event or a child job, sleeping, or polling. */
  sealed interface Suspension
      permits EventSuspension, SleepSuspension, JobSuspension, PollSuspension {

    /** The waiting step. */
    String stepName();

    /**
     * The deadline of an event wait or a wait for a child job ({@code null} if none), the wake-up
     * time of a sleep, or the time of a poll's next check.
     */
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

  /**
   * A step waiting for the child job it started.
   *
   * @param stepName the step that started the child
   * @param childJobId the child
   * @param waitUntil the deadline of the wait, {@code null} if none
   */
  record JobSuspension(String stepName, UUID childJobId, Instant waitUntil) implements Suspension {

    @Override
    public String toString() {
      return "to wait for job " + childJobId + (waitUntil == null ? "" : " until " + waitUntil);
    }
  }

  /**
   * A poll waiting for its next check.
   *
   * @param stepName the polling step
   * @param check the number of the check that was not ready (or of the last check, if the job was
   *     claimed before the next one was due)
   * @param waitUntil the time of the next check
   * @param resumed whether the poll was already waiting when the job was claimed: then neither the
   *     claim nor this suspension is recorded in the job history
   */
  record PollSuspension(String stepName, int check, Instant waitUntil, boolean resumed)
      implements Suspension {

    @Override
    public String toString() {
      return "to poll again at " + waitUntil;
    }
  }
}
