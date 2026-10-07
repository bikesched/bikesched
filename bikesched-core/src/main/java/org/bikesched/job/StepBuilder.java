// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

/**
 * Builds a step of a job, created by {@link Job#newStep(String, Class)}: optionally with a
 * compensation, then run.
 *
 * <pre>{@code
 * PaymentReservation reservation =
 *     job.newStep("reserve-payment", PaymentReservation.class)
 *         .compensateWith((step, r) -> payments.release(r))
 *         .run(step -> payments.reserve(order));
 * }</pre>
 *
 * @param <T> the type of the step result
 */
public interface StepBuilder<T> {

  /**
   * Sets the compensation undoing the step (optional). If the job is cancelled, or fails
   * permanently, after the step completed, bikesched compensates the job before it ends: the job
   * becomes {@code COMPENSATING}, its handler runs again in compensation mode (see {@link
   * Job#isCompensating()}), and the compensations of all completed steps run in reverse order, each
   * receiving its step's stored result. Jobs without compensations end right away.
   *
   * @param compensation undoes the step
   * @return this builder
   */
  StepBuilder<T> compensateWith(CompensationHandler<? super T> compensation);

  /**
   * Runs the step like {@link Job#step(String, Class, StepHandler)}: executes it unless it
   * completed in an earlier attempt of the job, whose stored result is returned instead.
   *
   * @param handler executes the step
   * @return the step result, may be {@code null}
   * @throws Exception if the step failed
   * @throws IllegalStateException if called while a step handler or compensation of the job runs
   */
  T run(StepHandler<T> handler) throws Exception;
}
