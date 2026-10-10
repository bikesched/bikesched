// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

/**
 * A {@link TypedJobFailureHandler} that carries its job type, for class-based failure handlers
 * registered without a separate type argument. One class can implement it next to {@link
 * JobTypeHandler} (both share {@link #type()}); it is registered for each on its own:
 *
 * <pre>{@code
 * class ChargeHandler implements JobTypeHandler<Charge>, JobTypeFailureHandler<Charge> {
 *   public JobType<Charge> type() { return CHARGE; }
 *   public void handle(Job job, Charge charge) { payments.charge(charge); }
 *   public void handle(FailedJob failed, Charge charge) { deadLetters.insert(failed.id(), charge); }
 * }
 *
 * var handler = new ChargeHandler();
 * bikesched.registerJobHandler(handler).registerJobFailureHandler(handler);
 * }</pre>
 *
 * @param <T> the payload type
 * @see org.bikesched.Bikesched#registerJobFailureHandler(JobTypeFailureHandler)
 */
public interface JobTypeFailureHandler<T> extends TypedJobFailureHandler<T> {

  /**
   * Returns the job type whose failed jobs this handler handles.
   *
   * @return the job type and its payload class
   */
  JobType<T> type();
}
