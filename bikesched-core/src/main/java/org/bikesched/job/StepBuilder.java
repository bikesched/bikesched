// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import org.bikesched.RetryBackoff;
import org.jspecify.annotations.Nullable;

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
public interface StepBuilder<T extends @Nullable Object> {

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
   * Sets the delay before the job is retried after this step failed (optional), replacing the
   * node's {@link RetryBackoff} for this step. It receives the number of failed attempts of the
   * step. A delay set with {@link JobFailure#retryAfter(java.time.Duration)} still takes
   * precedence.
   *
   * @param backoff the delay of the retry after the step failed
   * @return this builder
   */
  StepBuilder<T> retryBackoff(RetryBackoff backoff);

  /**
   * Sets a handler that runs when the step failed in the last attempt of its job (optional), before
   * the step and the job are recorded as failed. The handler may {@linkplain
   * FailingStep#resolve(Object) resolve} the step with a fallback result instead, so the job
   * continues. The step is {@code FAILING} meanwhile; a handler that throws runs again in an extra
   * attempt of the job, up to the node's {@link
   * org.bikesched.BikeschedBuilder#failureHandlerAttempts(int) failureHandlerAttempts} runs.
   * Without a handler a failing step simply fails the job.
   *
   * @param handler the failure handler, must be idempotent
   * @return this builder
   */
  StepBuilder<T> onFailure(StepFailureHandler<T> handler);

  /**
   * Runs the step like {@link Job#step(String, Class, StepHandler)}: executes it unless it
   * completed in an earlier attempt of the job, whose stored result is returned instead.
   *
   * @param handler executes the step
   * @return the step result, may be {@code null}
   * @throws Exception if the step failed
   * @throws IllegalStateException if called while a step handler or compensation of the job runs
   */
  @Nullable T run(StepHandler<T> handler) throws Exception;
}
