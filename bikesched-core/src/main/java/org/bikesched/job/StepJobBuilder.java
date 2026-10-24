// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.bikesched.BikeschedException;
import org.jspecify.annotations.Nullable;

/**
 * Builds a child job started exactly once as a durable step of its parent, obtained via {@link
 * Job#newJob(String)} or {@link Job#newJob(TypedJob)}:
 *
 * <pre>{@code
 * UUID charge = job.newJob(CHARGE).payload(order.payment()).stepName("charge").schedule();
 * Receipt receipt = job.waitForJob(charge, Receipt.class).orElseThrow();
 * }</pre>
 *
 * <p>The first run of the parent's handler stores the child and the step in one transaction; later
 * runs find the step and return the same child's id without scheduling again. Children are never
 * recurring and never stored in the caller's transaction: this builder has neither {@code
 * schedule(Recurrence)} nor {@code connection(…)}. It has no {@code handler(…)} either: a child
 * connects to a job name whose handler is registered on the parent's node; code that runs inline
 * belongs in a step ({@link Job#newStep(String, Class)}). Not thread-safe.
 *
 * @param <P> the payload type: {@code Object} for a job name given as a string, the payload class
 *     of a {@link TypedJob}
 */
public interface StepJobBuilder<P extends @Nullable Object>
    extends JobSettings<StepJobBuilder<P>, P> {

  /**
   * Sets the name of the step starting the child (optional, default {@code start <name>}). Set it
   * when a job starts several children of the same name: step names are unique within a job.
   *
   * @param stepName the step name, at most 200 characters
   * @return this builder
   */
  StepJobBuilder<P> stepName(String stepName);

  /**
   * Sets the compensation of the step starting the child (optional): if the parent is cancelled or
   * fails permanently after starting the child, the compensation receives the child's id, e.g. to
   * cancel it ({@code bikesched.cancel(childId, "order cancelled")}). It also runs while the child
   * is still running: that is the case worth compensating. Without a compensation, the child is
   * left alone.
   *
   * @param compensation undoes starting the child
   * @return this builder
   */
  StepJobBuilder<P> compensateWith(CompensationHandler<? super UUID> compensation);

  /**
   * Starts the child as soon as possible, unless an earlier run of the parent started it already.
   *
   * @return the child's id, the same on every run of the parent
   * @throws IllegalArgumentException if no handler is registered for the job name, or the default
   *     step name is longer than 200 characters
   * @throws IllegalStateException if the step name belongs to another step of the parent, if the
   *     parent is an occurrence of a recurring job, or if called while a step handler or
   *     compensation of the parent runs
   * @throws BikeschedException if the child or the step cannot be stored
   */
  UUID schedule();

  /**
   * Starts the child at or after the given time, like {@link #schedule()}.
   *
   * @param at the earliest time the child may run
   * @return the child's id, the same on every run of the parent
   * @throws IllegalArgumentException if no handler is registered for the job name
   * @throws IllegalStateException see {@link #schedule()}
   * @throws BikeschedException if the child or the step cannot be stored
   */
  UUID schedule(Instant at);

  /**
   * Starts the child once the given delay has passed, like {@link #schedule()}.
   *
   * @param delay how long to wait before the child may run, not negative
   * @return the child's id, the same on every run of the parent
   * @throws IllegalArgumentException if the delay is negative or no handler is registered for the
   *     job name
   * @throws IllegalStateException see {@link #schedule()}
   * @throws BikeschedException if the child or the step cannot be stored
   */
  UUID schedule(Duration delay);
}
