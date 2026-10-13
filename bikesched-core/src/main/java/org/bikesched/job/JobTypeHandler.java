// SPDX-License-Identifier: Apache-2.0
package org.bikesched.job;

import org.jspecify.annotations.Nullable;

/**
 * A {@link TypedJobHandler} that carries its {@link JobType}, so it can be registered on its own,
 * e.g. all handler beans of a dependency injection container in a loop:
 *
 * <pre>{@code
 * final class OrderHandler implements JobTypeHandler<Order> {
 *     public JobType<Order> type() {
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
 * <p>Return a shared constant from {@link #type()} rather than creating the type in the handler:
 * code scheduling jobs of the type should not depend on the handler class.
 *
 * @param <T> the payload type
 * @see org.bikesched.Bikesched#registerJobHandler(JobTypeHandler)
 */
public interface JobTypeHandler<T extends @Nullable Object> extends TypedJobHandler<T> {

  /**
   * Returns the job type this handler executes.
   *
   * @return the job type and its payload class
   */
  JobType<T> type();
}
