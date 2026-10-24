// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The job settings plus the handler, for the builders of jobs scheduled through {@link
 * org.bikesched.Bikesched}: {@link JobBuilder}, {@link TransactionalJobBuilder} and {@link
 * IdempotentJobBuilder}. A {@link StepJobBuilder} has no handler: a child job connects to a job
 * type registered on its own, and inline code belongs in a step ({@link Job#newStep(String,
 * Class)}).
 *
 * @param <B> the concrete builder type
 * @param <P> the payload type: {@code Object} for a job name given as a string, the payload class
 *     of a {@link TypedJob}
 */
public interface JobHandlerSettings<B extends JobHandlerSettings<B, P>, P extends @Nullable Object>
    extends JobSettings<B, P> {

  /**
   * Sets the handler executing jobs of this name (optional if one is already registered). It is
   * registered under the job name when the job is scheduled. <strong>If a handler is already
   * registered for the name, that one is kept and this handler is ignored</strong>, so the same
   * {@code newJob(name).handler(...).schedule()} code can run repeatedly (lambdas are usually a new
   * instance on every call).
   *
   * @param handler executes jobs of this name
   * @return this builder
   * @see org.bikesched.Bikesched#registerJobHandler(String, JobHandler)
   */
  B handler(JobHandler handler);

  /**
   * Sets a handler that does not need the job itself (e.g. no payload).
   *
   * @param handler executes jobs of this name
   * @return this builder
   * @see #handler(JobHandler)
   */
  default B handler(Runnable handler) {
    Objects.requireNonNull(handler, "handler");
    return handler(ignored -> handler.run());
  }

  /**
   * Sets a handler receiving the payload mapped to the payload type (the {@link TypedJob}'s payload
   * class, {@code Object} for a job name given as a string). Registered like {@link
   * #handler(JobHandler)}.
   *
   * @param handler executes jobs of this name
   * @return this builder
   * @see org.bikesched.Bikesched#registerJobHandler(TypedJob, TypedJobHandler)
   */
  B handler(TypedJobHandler<? super P> handler);

  /**
   * Sets the failure handler of this job name (optional). It is registered under the job name when
   * the job is scheduled, like {@link #handler(JobHandler)}: <strong>if a failure handler is
   * already registered for the name, that one is kept and this one is ignored</strong>.
   *
   * @param handler handles failed jobs of this name
   * @return this builder
   * @see org.bikesched.Bikesched#registerJobFailureHandler(String, JobFailureHandler)
   */
  B failureHandler(JobFailureHandler handler);

  /**
   * Sets a failure handler receiving the payload mapped to the payload type (the {@link TypedJob}'s
   * payload class, {@code Object} for a job name given as a string). Registered like {@link
   * #failureHandler(JobFailureHandler)}.
   *
   * @param handler handles failed jobs of this name
   * @return this builder
   * @see org.bikesched.Bikesched#registerJobFailureHandler(TypedJob, TypedJobFailureHandler)
   */
  B failureHandler(TypedJobFailureHandler<? super P> handler);
}
