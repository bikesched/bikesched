// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import org.jspecify.annotations.Nullable;

/**
 * A {@link TypedJobHandler} that carries its {@link TypedJob}, so it can be registered on its own,
 * e.g. all handler beans of a dependency injection container in a loop:
 *
 * <pre>{@code
 * final class OrderHandler implements StandaloneJobHandler<Order> {
 *     public TypedJob<Order> typedJob() {
 *         return Jobs.ORDER;   // a shared constant: producers use it with newJob(Jobs.ORDER)
 *     }
 *
 *     public void handle(Job job, Order order) {
 *         shipping.ship(order);
 *     }
 * }
 *
 * bikesched.registerJobHandler(new OrderHandler());
 * }</pre>
 *
 * <p>Return a shared constant from {@link #typedJob()} rather than creating it in the handler: code
 * scheduling jobs of the name should not depend on the handler class.
 *
 * @param <T> the payload type
 * @see org.bikesched.Bikesched#registerJobHandler(StandaloneJobHandler)
 */
public interface StandaloneJobHandler<T extends @Nullable Object> extends TypedJobHandler<T> {

  /**
   * Returns the typed job this handler executes.
   *
   * @return the job name and its payload class
   */
  TypedJob<T> typedJob();
}
