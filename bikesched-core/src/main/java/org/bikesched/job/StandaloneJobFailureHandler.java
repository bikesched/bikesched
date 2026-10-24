// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import org.jspecify.annotations.Nullable;

/**
 * A {@link TypedJobFailureHandler} that carries its {@link TypedJob}, for class-based failure
 * handlers registered without a separate name argument. One class can implement it next to {@link
 * StandaloneJobHandler} (both share {@link #typedJob()}); it is registered for each on its own:
 *
 * <pre>{@code
 * class ChargeHandler implements StandaloneJobHandler<Charge>, StandaloneJobFailureHandler<Charge> {
 *   public TypedJob<Charge> typedJob() { return CHARGE; }
 *   public void handle(Job job, Charge charge) { payments.charge(charge); }
 *   public void handle(FailedJob failed, Charge charge) { deadLetters.insert(failed.id(), charge); }
 * }
 *
 * var handler = new ChargeHandler();
 * bikesched.registerJobHandler(handler).registerJobFailureHandler(handler);
 * }</pre>
 *
 * @param <T> the payload type
 * @see org.bikesched.Bikesched#registerJobFailureHandler(StandaloneJobFailureHandler)
 */
public interface StandaloneJobFailureHandler<T extends @Nullable Object>
    extends TypedJobFailureHandler<T> {

  /**
   * Returns the typed job whose failed jobs this handler handles.
   *
   * @return the job name and its payload class
   */
  TypedJob<T> typedJob();
}
